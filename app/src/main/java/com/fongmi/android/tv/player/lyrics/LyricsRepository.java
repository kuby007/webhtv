package com.fongmi.android.tv.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local-only lyrics repository: remembered choices, the lyrics cache and
 * sidecar .lrc/.ttml files next to local media. Online lyrics sources were
 * removed with the lyrics downgrade; network matching no longer happens here.
 */
public class LyricsRepository {

    public interface Callback {
        void onResult(LyricsResult result);
    }

    private static final String TAG = "lyrics";

    public void load(LyricsRequest request, Callback callback) {
        loadLocal(request, callback);
    }

    public void loadPreferWord(LyricsRequest request, Callback callback) {
        loadLocal(request, callback);
    }

    public void loadPreferWord(LyricsRequest request, boolean forceRefresh, Callback callback) {
        loadLocal(request, callback);
    }

    public void remember(LyricsRequest request, LyricsResult result) {
        if (request == null || result == null || !result.isValid()) return;
        writeChoice(request, result);
        writeCache(request, result);
    }

    public boolean hasChoice(LyricsRequest request) {
        LyricsResult choice = readChoice(request);
        return choice != null && choice.isValid() && choice.isCacheCurrent();
    }

    private void loadLocal(LyricsRequest request, Callback callback) {
        Task.execute(() -> {
            LyricsResult result = null;
            try {
                result = loadLocalSync(request);
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "load failed title=%s error=%s", request.getTitle(), e.getMessage());
            }
            LyricsResult finalResult = result;
            App.post(() -> callback.onResult(finalResult));
        });
    }

    private LyricsResult loadLocalSync(LyricsRequest request) {
        LyricsResult choice = readChoice(request);
        if (choice != null && choice.isValid() && choice.isCacheCurrent()) return choice;
        LyricsResult local = readLocal(request);
        if (local != null && local.isValid()) {
            writeCache(request, local);
            return local;
        }
        LyricsResult cached = readCache(request);
        if (cached != null && cached.isValid() && cached.isCacheCurrent()) return cached;
        if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "local miss title=%s artist=%s parse=%s", request.getTitle(), request.getArtist(), request.getParseInfo());
        return null;
    }

    private void writeCache(LyricsRequest request, LyricsResult result) {
        try {
            Path.write(cacheFile(request), App.gson().toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String normalize(String text) {
        String value = clean(text).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return "";
        value = ChineseText.toSimplified(Normalizer.normalize(value, Normalizer.Form.NFKC));
        value = value.replaceAll("\\.[a-z0-9]{2,5}$", "");
        value = value.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", "");
        value = value.replaceAll("(?i)(?<=[\\u4e00-\\u9fff])\\s*dj(?:[a-z0-9\\u4e00-\\u9fff]*版)?\\s*$", "");
        value = value.replaceAll("(?i)official|music video|video|audio|lyrics|lyric|mv|flac|mp3|lossless|tv size|short ver\\.?|full ver\\.?|opening|ending|op|ed|feat\\.?|featuring", "");
        value = value.replaceAll("(?i)(?:live|cover|remix|instrumental|karaoke|off vocal)(?: version| ver\\.?|版)?$", "");
        value = value.replaceAll("(?:现场版|翻唱版|伴奏版|纯音乐版|原唱版)$", "");
        value = value.replaceAll("(?i)tvアニメ|テレビアニメ|アニメ|オープニング|エンディング|主題歌|挿入歌", "");
        value = value.replaceAll("[\\s_\\-.,:;!?/\\\\|+~`'\"#@$%^&*=<>，。！？、·：；“”‘’《》〈〉]+", "");
        return value.trim();
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    public static boolean isAcceptableMatch(LyricsRequest request, LyricsResult result) {
        if (request == null || result == null || !result.isValid()) return false;
        String wanted = normalize(request.getTitle());
        String actual = normalize(result.getTrackName());
        if (TextUtils.isEmpty(wanted) || TextUtils.isEmpty(actual)) return false;
        int titleScore = relatedTextScore(request.getTitle(), result.getTrackName(), 72, 46, -72);
        if (titleScore < 30) return false;
        int score = matchScore(request, result.getTrackName(), result.getArtistName(), result.getDurationMs() / 1000.0);
        return titleScore >= 72 ? score >= 0 : score >= 30;
    }

    public static int matchScore(LyricsRequest request, String actualTitle, String actualArtist, double actualSec) {
        if (request == null) return 0;
        int titleScore = relatedTextScore(request.getTitle(), actualTitle, 72, 46, -72);
        int artistScore = TextUtils.isEmpty(request.getArtist()) ? 0 : relatedTextScore(request.getArtist(), actualArtist, 28, 14, -12);
        int timeScore = durationScore(request.getDurationSec(), actualSec);
        int score = titleScore + artistScore + timeScore + titleQualityAdjustment(actualTitle);
        if (titleScore >= 72 && timeScore >= 20) score += 8;
        return score;
    }

    private static int relatedTextScore(String wanted, String actual, int exact, int related, int mismatch) {
        String a = normalize(wanted);
        String b = normalize(actual);
        if (TextUtils.isEmpty(a)) return 0;
        if (TextUtils.isEmpty(b)) return mismatch / 2;
        if (a.equals(b)) return exact;
        if (!a.contains(b) && !b.contains(a)) return mismatch;
        int shorter = Math.min(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        int longer = Math.max(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        if (shorter <= 1 || isSequenceOnly(a) || isSequenceOnly(b)) return mismatch;
        float ratio = shorter / (float) Math.max(1, longer);
        if (ratio >= 0.75f) return related;
        if (ratio >= 0.55f) return Math.max(18, related - 14);
        return mismatch / 2;
    }

    private static int durationScore(int wantedSec, double actualSec) {
        if (wantedSec <= 0 || actualSec <= 0) return 0;
        double delta = Math.abs(wantedSec - actualSec);
        if (delta <= 2) return 24;
        if (delta <= 5) return 20;
        if (delta <= 10) return 14;
        if (delta <= 20) return 4;
        if (delta <= 40) return -18;
        return -40;
    }

    private static int titleQualityAdjustment(String title) {
        String value = Normalizer.normalize(clean(title), Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return -40;
        if (isSequenceOnly(value)) return -120;
        int score = 0;
        int length = value.codePointCount(0, value.length());
        if (containsAny(value, "合集", "歌单", "盘点", "排行榜", "热歌榜", "频道", "最好听", "宝藏歌曲", "首歌曲")) score -= 70;
        if (length > 36) score -= 36;
        else if (length > 28) score -= 20;
        return score;
    }

    private static boolean isSequenceOnly(String text) {
        return clean(text).matches("(?i)^(?:p\\s*)?\\d{1,4}$");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value) && text.contains(value)) return true;
        return false;
    }

    private LyricsResult readCache(LyricsRequest request) {
        for (File file : cacheFiles(request)) {
            LyricsResult result = readResultFile(file);
            if (result == null || !isAcceptableMatch(request, result)) continue;
            if (!sameFile(file, cacheFile(request))) writeCache(request, result);
            return result;
        }
        return null;
    }

    private LyricsResult readChoice(LyricsRequest request) {
        LyricsResult result = readResultFile(choiceFile(request));
        if (isAcceptableMatch(request, result)) return result;
        result = readResultFile(legacyChoiceFile(request));
        if (isAcceptableMatch(request, result)) {
            writeChoice(request, result);
            return result;
        }
        result = readMatchingChoice(request);
        if (result != null) writeChoice(request, result);
        return result;
    }

    private void writeChoice(LyricsRequest request, LyricsResult result) {
        try {
            Path.write(choiceFile(request), App.gson().toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private LyricsResult readResultFile(File file) {
        if (!Path.exists(file)) return null;
        try {
            return App.gson().fromJson(Path.read(file), LyricsResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private LyricsResult readMatchingChoice(LyricsRequest request) {
        File[] files = choiceDir().listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return null;
        for (File file : files) {
            LyricsResult result = readResultFile(file);
            if (result == null || !result.isValid() || !result.isCacheCurrent() || !choiceMatches(request, result)) continue;
            return result;
        }
        return null;
    }

    private boolean choiceMatches(LyricsRequest request, LyricsResult result) {
        String requestTitle = LyricsParser.normalize(request.getTitle());
        String requestArtist = LyricsParser.normalize(request.getArtist());
        String resultTitle = LyricsParser.normalize(result.getTrackName());
        String resultArtist = LyricsParser.normalize(result.getArtistName());
        boolean directTitle = relatedText(requestTitle, resultTitle);
        boolean directArtist = TextUtils.isEmpty(requestArtist) || TextUtils.isEmpty(resultArtist) || relatedText(requestArtist, resultArtist);
        if (directTitle && directArtist) return true;
        return !TextUtils.isEmpty(requestArtist) && !TextUtils.isEmpty(resultArtist) && relatedText(requestTitle, resultArtist) && relatedText(requestArtist, resultTitle);
    }

    private boolean relatedText(String first, String second) {
        if (TextUtils.isEmpty(first) || TextUtils.isEmpty(second)) return false;
        return first.equals(second) || first.contains(second) || second.contains(first);
    }

    private LyricsResult readLocal(LyricsRequest request) {
        File source = sourceFile(request.getUrl());
        if (source == null) return null;
        File parent = source.getParentFile();
        if (parent == null) return null;
        List<String> bases = localBases(request, source);
        File ttml = findLocal(parent, bases, ".ttml");
        if (Path.exists(ttml)) {
            String text = TtmlConverter.toEnhancedLrc(Path.read(ttml));
            if (!TextUtils.isEmpty(text) && LyricsParser.hasTimedLine(text)) return new LyricsResult("Local TTML", request.getTitle(), request.getArtist(), request.getAlbum(), text, request.getDurationMs(), true, 104);
        }
        File lrc = findLocal(parent, bases, ".lrc");
        if (!Path.exists(lrc)) return null;
        String text = Path.read(lrc);
        if (TextUtils.isEmpty(text)) return null;
        boolean synced = LyricsParser.hasTimedLine(text);
        return new LyricsResult("Local", request.getTitle(), request.getArtist(), request.getAlbum(), text, request.getDurationMs(), synced, 100);
    }

    private List<String> localBases(LyricsRequest request, File source) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String sourceBase = fileBase(source.getName());
        String title = request.getTitle();
        String artist = request.getArtist();
        addLocalBase(values, sourceBase);
        addLocalBase(values, title);
        addLocalBase(values, request.displayKeyword());
        if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(title)) {
            addLocalBase(values, artist + " - " + title);
            addLocalBase(values, title + " - " + artist);
            addLocalBase(values, artist + "_" + title);
            addLocalBase(values, title + "." + sourceBase);
            addLocalBase(values, sourceBase + "." + title);
        }
        return new ArrayList<>(values);
    }

    private void addLocalBase(Set<String> values, String value) {
        String text = safe(value).trim();
        if (TextUtils.isEmpty(text)) return;
        values.add(text);
        String safe = text.replaceAll("[\\\\/:*?\"<>|]+", " ").replaceAll("\\s+", " ").trim();
        if (!TextUtils.isEmpty(safe)) values.add(safe);
    }

    private File findLocal(File parent, List<String> bases, String suffix) {
        for (String base : bases) {
            File file = new File(parent, base + suffix);
            if (Path.exists(file)) return file;
            file = new File(parent, base + suffix.toUpperCase(Locale.ROOT));
            if (Path.exists(file)) return file;
        }
        File[] files = parent.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(suffix)) continue;
            String base = fileBase(name);
            for (String value : bases) if (sameLocalBase(base, value)) return file;
        }
        return null;
    }

    private boolean sameLocalBase(String a, String b) {
        return normalizeLocalBase(a).equals(normalizeLocalBase(b));
    }

    private String normalizeLocalBase(String value) {
        return Normalizer.normalize(safe(value), Normalizer.Form.NFKC)
                .replace('　', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String fileBase(String name) {
        String value = safe(name);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private File sourceFile(String url) {
        try {
            if (TextUtils.isEmpty(url)) return null;
            if (url.startsWith("file://")) return new File(Uri.parse(url).getPath());
            if (url.startsWith("/")) return new File(url);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private File cacheFile(LyricsRequest request) {
        File dir = Path.cache("lyrics");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, request.stableSignature() + ".json");
    }

    private File legacyCacheFile(LyricsRequest request) {
        File dir = Path.cache("lyrics");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, request.signature() + ".json");
    }

    private List<File> cacheFiles(LyricsRequest request) {
        ArrayList<File> files = new ArrayList<>();
        addFile(files, cacheFile(request));
        addFile(files, legacyCacheFile(request));
        return files;
    }

    private File choiceFile(LyricsRequest request) {
        return new File(choiceDir(), request.stableSignature() + ".json");
    }

    private File legacyChoiceFile(LyricsRequest request) {
        return new File(choiceDir(), request.signature() + ".json");
    }

    private static File choiceDir() {
        File dir = new File(cacheDir(), "choices");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File cacheDir() {
        return Path.cache("lyrics");
    }

    private void addFile(List<File> files, File file) {
        if (file == null) return;
        for (File item : files) if (sameFile(item, file)) return;
        files.add(file);
    }

    private boolean sameFile(File first, File second) {
        if (first == null || second == null) return first == second;
        return TextUtils.equals(first.getAbsolutePath(), second.getAbsolutePath());
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }

    public static int cacheCount() {
        File[] files = cacheDir().listFiles((dir, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    public static int clearCache() {
        File dir = cacheDir();
        int count = cacheCount();
        Path.clear(dir);
        if (!dir.exists()) dir.mkdirs();
        return count;
    }
}
