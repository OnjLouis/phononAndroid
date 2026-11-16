package me.onj.phonon.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class UpdateClient {
    private static final String PRODUCT = "phonon-android";
    private static final String RELEASE_BASE = "https://github.com/OnjLouis/phononAndroid/releases/";
    private static final String METADATA_URL =
            RELEASE_BASE + "latest/download/phononAndroid-update.json";
    private static final int METADATA_LIMIT_BYTES = 64 * 1024;
    private static final long APK_LIMIT_BYTES = 400L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    static final class Candidate {
        final String version;
        final String url;
        final String sha256;
        final long size;

        Candidate(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private UpdateClient() {
    }

    static Candidate check(String currentVersion) throws Exception {
        require(!BuildConfig.UPDATE_KEY.isEmpty(), "This test build does not have a release update key.");
        byte[] metadata = readBounded(open(METADATA_URL), METADATA_LIMIT_BYTES);
        return parse(new String(metadata, StandardCharsets.UTF_8), currentVersion, BuildConfig.UPDATE_KEY);
    }

    static Candidate parse(String document, String currentVersion, String publicKey) throws Exception {
        JSONObject json = new JSONObject(document);
        int schema = json.getInt("schema");
        String product = json.getString("product");
        String version = json.getString("version");
        String url = json.getString("url");
        String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
        long size = json.getLong("size");
        String encodedSignature = json.getString("signature");

        require(schema == 1, "Unsupported update metadata version.");
        require(PRODUCT.equals(product), "The update metadata is for another product.");
        require(sha256.matches("[0-9a-f]{64}"), "The update metadata has an invalid digest.");
        require(size > 0 && size <= APK_LIMIT_BYTES, "The update package size is invalid.");
        require(url.equals(RELEASE_BASE + "download/v" + version + "/Phonon-Android-" + version + ".apk"),
                "The update package is not a Phonon GitHub release asset.");
        verifyMetadataSignature(
                signedPayload(schema, product, version, url, sha256, size),
                encodedSignature, publicKey);
        if (compareVersions(version, currentVersion) <= 0) {
            return null;
        }
        return new Candidate(version, url, sha256, size);
    }

    static File downloadAndVerify(
            Context context,
            Candidate candidate,
            ProgressListener listener) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the update folder.");
        }
        File[] stale = directory.listFiles();
        if (stale != null) {
            for (File file : stale) {
                if (file.isFile() && !file.delete()) {
                    throw new IOException("Could not remove an old update file.");
                }
            }
        }

        File partial = new File(directory, "Phonon-Android-" + candidate.version + "-partial.apk");
        File target = new File(directory, "Phonon-Android-" + candidate.version + ".apk");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = open(candidate.url);
        long total = 0;
        try {
            long declaredLength = connection.getContentLengthLong();
            require(declaredLength < 0 || declaredLength == candidate.size,
                    "The update download has an unexpected size.");
            try (java.io.InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    total += count;
                    require(total <= candidate.size && total <= APK_LIMIT_BYTES,
                            "The update download is larger than expected.");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    listener.onProgress(total, candidate.size);
                }
            }
        } catch (Throwable error) {
            partial.delete();
            throw error;
        } finally {
            connection.disconnect();
        }
        try {
            require(total == candidate.size, "The update download is incomplete.");
            require(candidate.sha256.equals(hex(digest.digest())), "The downloaded update failed its SHA-256 check.");
            validatePackage(context, partial, candidate.version);
            require(partial.renameTo(target), "Could not finish the update download.");
            return target;
        } finally {
            partial.delete();
        }
    }

    static boolean canInstallPackages(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    static Intent unknownSourcesSettingsIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
    }

    static void openInstaller(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".update-files",
                apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    static int compareVersions(String left, String right) {
        List<Integer> leftParts = parseVersion(left);
        List<Integer> rightParts = parseVersion(right);
        int count = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < count; index++) {
            int comparison = Integer.compare(
                    index < leftParts.size() ? leftParts.get(index) : 0,
                    index < rightParts.size() ? rightParts.get(index) : 0);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    static byte[] signedPayload(
            int schema,
            String product,
            String version,
            String url,
            String sha256,
            long size) {
        String value = schema + "\n" + product + "\n" + version + "\n" + url
                + "\n" + sha256 + "\n" + size;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Integer> parseVersion(String value) {
        require(value != null && value.matches("\\d+(?:\\.\\d+){1,3}"),
                "Invalid version: " + value);
        String[] parts = value.split("\\.");
        List<Integer> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(Integer.parseInt(part));
        }
        return result;
    }

    static void verifyMetadataSignature(byte[] payload, String encodedSignature, String publicKey) throws Exception {
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(payload);
        require(verifier.verify(Base64.getDecoder().decode(encodedSignature)),
                "The update metadata signature is not valid.");
    }

    private static HttpURLConnection open(String address) throws IOException {
        requireHttps(address);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setRequestProperty("User-Agent", "Phonon-Android/" + BuildConfig.VERSION_NAME);
        connection.connect();
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode > 299) {
            connection.disconnect();
            throw new IOException("The update server returned HTTP " + responseCode + ".");
        }
        requireHttps(connection.getURL().toString());
        return connection;
    }

    private static byte[] readBounded(HttpURLConnection connection, int limit) throws IOException {
        try {
            long length = connection.getContentLengthLong();
            require(length < 0 || length <= limit, "The update metadata is unexpectedly large.");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            try (java.io.InputStream input = connection.getInputStream()) {
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    require(output.size() + count <= limit,
                            "The update metadata is unexpectedly large.");
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    private static void validatePackage(Context context, File apk, String expectedVersion)
            throws PackageManager.NameNotFoundException {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getPath(), flags);
        require(archive != null, "Android could not read the downloaded update package.");
        require(context.getPackageName().equals(archive.packageName),
                "The downloaded update is not Phonon for Android.");
        require(expectedVersion.equals(archive.versionName),
                "The downloaded update has the wrong version.");
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        require(archive.getLongVersionCode() > installed.getLongVersionCode(), "The update build is not newer.");
        Set<String> installedSigners = signerDigests(installed);
        Set<String> archiveSigners = signerDigests(archive);
        installedSigners.retainAll(archiveSigners);
        require(!installedSigners.isEmpty(),
                "The downloaded update is not signed by the installed Phonon application.");
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerDigests(PackageInfo info) {
        android.content.pm.Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures == null) {
            return result;
        }
        for (android.content.pm.Signature signature : signatures) {
            try {
                result.add(hex(MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())));
            } catch (Exception error) {
                throw new IllegalStateException("Could not verify the update signer.", error);
            }
        }
        return result;
    }

    private static void requireHttps(String address) {
        try {
            URL url = new URL(address);
            require("https".equalsIgnoreCase(url.getProtocol()),
                    "The update address does not use HTTPS.");
        } catch (IOException error) {
            throw new IllegalArgumentException("The update address is invalid.", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
