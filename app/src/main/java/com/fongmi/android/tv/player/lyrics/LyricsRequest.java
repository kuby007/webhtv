package com.fongmi.android.tv.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.player.PlayerManager;
import com.github.catvod.utils.Util;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class LyricsRequest {

    private final String key;
    private final String url;
    private final String title;
    private final String artist;
    private final String album;
    private final long durationMs;
    private final String parseInfo;
    private final String sourceText;

    public LyricsRequest(String key, String url, String title, String artist, String album, long durationMs) {
        this(key, url, title, artist, album, durationMs, "direct", joinSourceText(title, artist));
    }

    private LyricsRequest(String key, String url, String title, String artist, String album, long durationMs, String parseInfo) {
        this(key, url, title, artist, album, durationMs, parseInfo, joinSourceText(title, artist));
    }

    private LyricsRequest(String key, String url, String title, String artist, String album, long durationMs, String parseInfo, String sourceText) {
        this.key = clean(key);
        this.url = clean(url);
        this.title = cleanTitle(title, url);
        this.artist = cleanArtist(artist);
        this.album = clean(album);
        this.durationMs = durationMs > 0 && durationMs != C.TIME_UNSET ? durationMs : 0;
        this.parseInfo = clean(parseInfo);
        this.sourceText = clean(sourceText);
    }

    public static LyricsRequest from(PlayerManager player) {
        MediaMetadata metadata = player.getMetadata();
        String url = player.getUrl();
        String title = metadata == null || metadata.title == null ? "" : metadata.title.toString();
        String artist = metadata == null || metadata.artist == null ? "" : metadata.artist.toString();
        Candidate candidate = selectBestCandidate(player.getKey(), title, artist, url);
        return new LyricsRequest(player.getKey(), url, candidate.title, candidate.artist, "", player.getDuration(), candidate.info(), joinSourceText(title, artist));
    }

    public String displayKeyword() {
        if (TextUtils.isEmpty(artist)) return title;
        return artist + " - " + title;
    }

    public String getKey() {
        return key;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getParseInfo() {
        return parseInfo;
    }

    public int getDurationSec() {
        return durationMs <= 0 ? 0 : (int) Math.round(durationMs / 1000.0);
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(title);
    }

    public String signature() {
        return Util.md5(String.join("|", key, url, title, artist, album, String.valueOf(getDurationSec())));
    }

    public String stableSignature() {
        String mediaKey = identityText(key);
        String titleKey = identityText(title);
        String artistKey = identityText(artist);
        String albumKey = identityText(album);
        if (!TextUtils.isEmpty(mediaKey)) return Util.md5("lyrics-v3|key-meta|" + mediaKey + "|" + titleKey + "|" + artistKey + "|" + albumKey);
        return Util.md5("lyrics-v2|meta|" + titleKey + "|" + artistKey + "|" + albumKey);
    }

    public String contentSignature() {
        return stableSignature();
    }

    private static String clean(String text) {
        return Objects.toString(text, "").trim();
    }

    private static String joinSourceText(String... values) {
        List<String> lines = new ArrayList<>();
        for (String value : values) {
            for (String line : clean(value).split("\\n")) {
                String item = line.trim();
                if (!TextUtils.isEmpty(item) && !containsKeyword(lines, item)) lines.add(item);
            }
        }
        return String.join("\n", lines);
    }

    private static boolean isLikelyNonTitleToken(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return true;
        return containsAny(value, "音乐", "歌单", "合集", "mv", "video", "official", "频道", "原唱", "翻唱", "现场", "直播");
    }

    private static boolean containsKeyword(List<String> keywords, String keyword) {
        String normalized = normalizeSearchText(keyword);
        for (String item : keywords) if (normalizeSearchText(item).equals(normalized)) return true;
        return false;
    }

    private static String stripSearchNoise(String text) {
        String value = clean(text);
        value = value.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", " ");
        value = value.replaceAll("(?i)(?<=[\\u4e00-\\u9fff])\\s*dj(?:[a-z0-9\\u4e00-\\u9fff]*版)?\\s*$", " ");
        value = value.replaceAll("(?i)\\b(tv size|short ver\\.?|full ver\\.?|instrumental|karaoke|off vocal|cover|remix|live|opening|ending|op|ed)\\b", " ");
        value = value.replaceAll("(?i)tvアニメ|テレビアニメ|アニメ|オープニング|エンディング|主題歌|挿入歌", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    private static void addBracketArtistParts(List<String> values, String raw) {
        addBracketArtistParts(values, raw, '(', ')');
        addBracketArtistParts(values, raw, '（', '）');
        addBracketArtistParts(values, raw, '[', ']');
        addBracketArtistParts(values, raw, '【', '】');
    }

    private static void addBracketArtistParts(List<String> values, String raw, char open, char close) {
        String value = clean(raw);
        int start = value.indexOf(open);
        while (start >= 0) {
            int end = value.indexOf(close, start + 1);
            if (end <= start) break;
            addDelimitedArtistParts(values, value.substring(start + 1, end));
            start = value.indexOf(open, end + 1);
        }
    }

    private static void addDelimitedArtistParts(List<String> values, String raw) {
        for (String item : clean(raw).split("(?i)\\s*(?:/|／|,|、|&|feat\\.?|featuring|with|;|；|\\|)\\s*")) {
            String value = normalizeSearchText(item);
            if (TextUtils.isEmpty(value) || isLikelyNonTitleToken(value) || containsKeyword(values, value)) continue;
            values.add(value);
        }
    }

    private static String normalizeSearchText(String text) {
        return ChineseText.toSimplified(Normalizer.normalize(stripSearchNoise(text), Normalizer.Form.NFKC))
                .replace('　', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanArtist(String text) {
        String value = clean(text);
        if (value.equalsIgnoreCase("unknown artist")) return "";
        if (value.matches(".*第\\s*\\d+\\s*[集期话話].*")) return "";
        return ChineseText.toSimplified(value);
    }

    private static String cleanTitle(String title, String url) {
        String value = clean(title);
        if (TextUtils.isEmpty(value) || value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://")) value = fileName(url);
        if (TextUtils.isEmpty(value)) value = fileName(title);
        return stripExtension(value);
    }

    private static String fileName(String url) {
        try {
            if (TextUtils.isEmpty(url)) return "";
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String name = TextUtils.isEmpty(path) ? url : new File(path).getName();
            return Uri.decode(name);
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripExtension(String name) {
        String value = clean(name);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot <= 6) value = value.substring(0, dot);
        return value.trim();
    }

    private static Candidate selectBestCandidate(String key, String rawTitle, String rawArtist, String url) {
        ArrayList<Candidate> candidates = new ArrayList<>();
        String title = cleanTitle(rawTitle, url);
        String artist = cleanArtist(rawArtist);
        boolean titleContainer = isContainerTitle(title);
        boolean titleVideo = isVideoContext(title);
        boolean artistEpisode = isLikelyEpisode(artist);
        boolean titleDelimited = splitDelimited(stripKnownPrefixes(title).text) != null;
        int metadataConfidence = titleContainer ? 32 : titleVideo ? 46 : artistEpisode ? 52 : TextUtils.isEmpty(artist) && titleDelimited ? 50 : 82;
        addMediaWorkTitleCandidate(candidates, title, artist);
        addBookPrefixTitleCandidate(candidates, artist);
        addBookTitleCandidate(candidates, title, "metadataTitle", TextUtils.isEmpty(artist) ? 118 : 92);
        addBookTitleCandidate(candidates, artist, "metadataArtist", 118);
        addCandidate(candidates, new Candidate(title, normalizeArtistFromEpisode(title, artist), metadataConfidence, "metadata", "pair", title));
        addMediaPrefixArtistTitleCandidate(candidates, title, "metadataTitle", TextUtils.isEmpty(artist) ? 94 : 64);
        addFieldCandidates(candidates, title, "metadataTitle", titleContainer ? 24 : titleVideo ? 38 : 70, TextUtils.isEmpty(artist) ? Bias.ARTIST_TITLE : Bias.NEUTRAL);
        if (titleContainer) addThemedPlaylistEpisodeCandidate(candidates, title, artist);
        if (titleContainer || artistEpisode || titleVideo) {
            addMediaPrefixArtistTitleCandidate(candidates, artist, "metadataArtist", artistEpisode ? 98 : titleContainer ? 92 : titleVideo ? 88 : 84);
            addFieldCandidates(candidates, artist, "metadataArtist", artistEpisode ? 90 : titleContainer ? 86 : titleVideo ? 82 : 74, Bias.TITLE_ARTIST);
            addContextArtistTitleCandidate(candidates, key, title, artist, artistEpisode ? 108 : titleContainer ? 98 : titleVideo ? 94 : 96);
        }
        addLeadingBracketCandidate(candidates, artist, "metadataArtist", titleVideo || titleContainer ? 88 : 68);
        addLeadingBracketCandidate(candidates, title, "metadataTitle", titleVideo ? 70 : 56);
        addFieldCandidates(candidates, fileName(url), "urlName", 34, Bias.NEUTRAL);
        Candidate best = bestCandidate(candidates);
        if (best != null) return best;
        return new Candidate(title, artist, 0, "fallback", "direct", title);
    }

    private static void addFieldCandidates(List<Candidate> candidates, String raw, String source, int baseConfidence, Bias bias) {
        String value = clean(raw);
        if (TextUtils.isEmpty(value) || baseConfidence <= 0) return;
        SequenceText media = stripMediaPrefix(value);
        SequenceText sequence = stripSequence(media.text);
        String text = clean(sequence.text);
        if (TextUtils.isEmpty(text)) return;
        SplitText split = splitDelimited(text);
        if (split != null) {
            int titleArtist = baseConfidence + (sequence.hadPrefix ? 14 : 0) + (bias == Bias.TITLE_ARTIST ? 10 : bias == Bias.ARTIST_TITLE ? -8 : 0) + (media.hadPrefix ? -28 : 0);
            int artistTitle = baseConfidence - 18 + (bias == Bias.ARTIST_TITLE ? 24 : bias == Bias.TITLE_ARTIST ? -8 : 0) + (media.hadPrefix ? 32 : 0);
            boolean hadPrefix = media.hadPrefix || sequence.hadPrefix;
            addCandidate(candidates, new Candidate(split.left, split.right, titleArtist, source, hadPrefix ? "sequence-title-artist" : "title-artist", raw));
            addCandidate(candidates, new Candidate(split.right, split.left, artistTitle, source, hadPrefix ? "sequence-artist-title" : "artist-title", raw));
            return;
        }
        boolean hadPrefix = media.hadPrefix || sequence.hadPrefix;
        int titleOnly = baseConfidence + (sequence.hadPrefix ? 6 : 0);
        addCandidate(candidates, new Candidate(text, "", titleOnly, source, hadPrefix ? "sequence-title-only" : "title-only", raw));
    }

    private static void addMediaPrefixArtistTitleCandidate(List<Candidate> candidates, String raw, String source, int confidence) {
        String value = clean(raw);
        if (TextUtils.isEmpty(value) || confidence <= 0) return;
        SequenceText prefixed = stripMediaPrefix(value);
        if (!prefixed.hadPrefix) return;
        SplitText split = splitDelimited(stripSequence(prefixed.text).text);
        if (split == null || isNoiseToken(split.left) || isNoiseToken(split.right)) return;
        addCandidate(candidates, new Candidate(split.right, split.left, confidence, source, "media-prefix-artist-title", raw));
    }

    private static void addBookTitleCandidate(List<Candidate> candidates, String raw, String source, int confidence) {
        String value = stripKnownPrefixes(clean(raw)).text;
        if (TextUtils.isEmpty(value) || confidence <= 0) return;
        BookText book = firstBookText(value);
        if (book == null) return;
        String prefix = cleanNamePart(value.substring(0, book.start));
        String suffix = cleanNamePart(value.substring(book.end + 1));
        String artist = !isLikelyNonTitleToken(prefix) ? prefix : "";
        if (TextUtils.isEmpty(artist)) artist = artistFromBookSuffix(suffix);
        addCandidate(candidates, new Candidate(book.text, artist, confidence, source, "book-title", raw));
    }

    private static void addMediaWorkTitleCandidate(List<Candidate> candidates, String metadataTitle, String rawEpisode) {
        SplitText split = splitDelimited(stripKnownPrefixes(rawEpisode).text);
        if (split == null || !isMediaWorkDescription(split.right)) return;
        String title = cleanNamePart(split.left);
        if (TextUtils.isEmpty(title) || isNoiseToken(title) || isContainerTitle(title) || isVideoContext(title) || title.length() > 48) return;
        addCandidate(candidates, new Candidate(title, "", 128, "metadataArtist", "media-work-title", rawEpisode));
    }

    private static void addBookPrefixTitleCandidate(List<Candidate> candidates, String rawEpisode) {
        String value = stripKnownPrefixes(clean(rawEpisode)).text;
        BookText book = firstBookText(value);
        if (book == null || book.start <= 0 || book.end >= value.length() - 1) return;
        String title = cleanNamePart(value.substring(0, book.start)).replaceFirst("\\s*[-–—－]+\\s*$", "").trim();
        String suffix = Normalizer.normalize(clean(value.substring(book.end + 1)), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(title) || title.length() > 48 || isNoiseToken(title) || isContainerTitle(title) || isVideoContext(title)) return;
        if (!containsAny(suffix, "主题", "片尾", "片头", "插曲", "原声", "电影", "电视", "剧集", "动画", "动漫", "中文", "英文")) return;
        addCandidate(candidates, new Candidate(title, "", 130, "metadataArtist", "book-prefix-title", rawEpisode));
    }

    private static boolean isMediaWorkDescription(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return false;
        BookText book = firstBookText(value);
        if (book != null && book.start == 0 && book.end < value.length() - 1) return true;
        return hasBookBracket(value) && isLikelyNonTitleToken(value)
                || containsAny(value, "主题曲", "片尾曲", "片头曲", "插曲", "影视原声", "电影原声", "电视剧原声", "动画原声", "ost");
    }

    private static String artistFromBookSuffix(String suffix) {
        String value = clean(suffix);
        int index = firstDashIndex(value);
        if (index == 0 && value.length() > 1) value = value.substring(1).trim();
        else if (index > 0) value = value.substring(index + 1).trim();
        value = cleanNamePart(value);
        return isLikelyNonTitleToken(value) ? "" : value;
    }

    private static void addThemedPlaylistEpisodeCandidate(List<Candidate> candidates, String containerTitle, String rawEpisode) {
        if (!isThemedPlaylistContainer(containerTitle)) return;
        String episode = clean(rawEpisode);
        if (TextUtils.isEmpty(episode) || episode.length() > 96) return;
        SequenceText sequence = stripSequence(episode);
        String text = stripTrailingYearSegment(sequence.text);
        int index = firstDashIndex(text);
        if (index <= 0) return;
        String title = cleanNamePart(text.substring(0, index));
        if (TextUtils.isEmpty(title) || isNoiseToken(title) || title.length() > 36) return;
        addCandidate(candidates, new Candidate(title, "", 94, "metadataArtist", "themed-playlist-title", rawEpisode));
    }

    private static void addContextArtistTitleCandidate(List<Candidate> candidates, String key, String metadataTitle, String rawEpisode, int confidence) {
        String episode = clean(rawEpisode);
        if (TextUtils.isEmpty(episode) || confidence <= 0) return;
        SequenceText sequence = stripSequence(episode);
        SplitText split = splitDelimited(sequence.text);
        if (split != null && isMediaWorkDescription(split.right)) return;
        if (split == null || !preferArtistTitle(key, metadataTitle, split)) return;
        addCandidate(candidates, new Candidate(split.right, split.left, confidence, "metadataArtist", sequence.hadPrefix ? "sequence-artist-title-context" : "artist-title-context", rawEpisode));
    }

    private static boolean preferArtistTitle(String key, String metadataTitle, SplitText split) {
        String title = normalizeSearchText(metadataTitle);
        String left = normalizeSearchText(split.left);
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(left)) return false;
        if (isSingerKey(key) && (title.equals(left) || isArtistCollectionTitle(metadataTitle, split.left))) return true;
        String bracketArtist = leadingBracketArtist(metadataTitle);
        if (!TextUtils.isEmpty(bracketArtist) && normalizeSearchText(bracketArtist).equals(left)) return true;
        return isArtistCollectionTitle(metadataTitle, split.left);
    }

    private static boolean isSingerKey(String key) {
        String value = Normalizer.normalize(clean(key), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return value.contains("/singer/") || value.contains("artist:");
    }

    private static boolean isArtistCollectionTitle(String title, String artist) {
        String value = Normalizer.normalize(clean(title), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String name = Normalizer.normalize(clean(artist), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value) || TextUtils.isEmpty(name) || !value.contains(name)) return false;
        return containsAny(value, "热门歌曲", "好听的", "个人专辑", "mv合集", "音乐合集", "歌手", "全部歌曲");
    }

    private static String leadingBracketArtist(String text) {
        String value = clean(text);
        if (!(value.startsWith("【") || value.startsWith("[") || value.startsWith("（") || value.startsWith("("))) return "";
        char close = value.startsWith("【") ? '】' : value.startsWith("[") ? ']' : value.startsWith("（") ? '）' : ')';
        int end = value.indexOf(close);
        return end <= 0 ? "" : firstUsefulToken(value.substring(1, end));
    }

    private static void addLeadingBracketCandidate(List<Candidate> candidates, String raw, String source, int confidence) {
        String value = clean(raw);
        if (TextUtils.isEmpty(value) || confidence <= 0) return;
        if (!(value.startsWith("【") || value.startsWith("[") || value.startsWith("（") || value.startsWith("("))) return;
        char close = value.startsWith("【") ? '】' : value.startsWith("[") ? ']' : value.startsWith("（") ? '）' : ')';
        int end = value.indexOf(close);
        if (end <= 0 || end >= value.length() - 1) return;
        String bracket = cleanNamePart(value.substring(1, end));
        SequenceText sequence = stripSequence(value.substring(end + 1));
        if (isTrackSequenceMarker(bracket) && sequence.hadPrefix) {
            String title = cleanNamePart(sequence.text);
            if (!TextUtils.isEmpty(title)) addCandidate(candidates, new Candidate(title, "", confidence + 24, source, "bracket-sequence-title", raw));
            return;
        }
        String artist = firstUsefulToken(bracket);
        String title = cleanNamePart(value.substring(end + 1));
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(artist)) return;
        addCandidate(candidates, new Candidate(title, artist, confidence, source, "bracket-artist-title", raw));
    }

    private static boolean isTrackSequenceMarker(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return value.matches("^p\\s*0*\\d{1,4}$");
    }

    private static Candidate bestCandidate(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate == null || !candidate.isValid()) continue;
            if (best == null || candidate.confidence > best.confidence || candidate.confidence == best.confidence && candidate.title.length() < best.title.length()) best = candidate;
        }
        return best;
    }

    private static void addCandidate(List<Candidate> candidates, Candidate candidate) {
        if (candidate == null || !candidate.isValid()) return;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate item = candidates.get(i);
            if (!sameCandidate(item, candidate)) continue;
            if (candidate.confidence > item.confidence) candidates.set(i, candidate);
            return;
        }
        candidates.add(candidate);
    }

    private static boolean sameCandidate(Candidate a, Candidate b) {
        return normalizeSearchText(a.title).equals(normalizeSearchText(b.title)) && normalizeSearchText(a.artist).equals(normalizeSearchText(b.artist));
    }

    private static String cleanNamePart(String text) {
        String value = stripExtension(clean(text));
        value = value.replaceAll("^[《<「『\"'“‘]+|[》>」』\"'”’]+$", "");
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String cleanTitlePart(String text) {
        String value = ChineseText.toSimplified(stripSearchNoise(cleanNamePart(text)));
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String cleanSuggestion(String text) {
        String value = ChineseText.toSimplified(cleanNamePart(stripKnownPrefixes(text).text));
        value = stripSearchNoise(value);
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String firstUsefulToken(String text) {
        for (String item : clean(text).split("\\s*(?:/|／|,|、|;|；|\\|)\\s*")) {
            String value = cleanNamePart(item);
            if (!TextUtils.isEmpty(value) && !isNoiseToken(value)) return value;
        }
        return "";
    }

    private static boolean isNoiseToken(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return true;
        String stripped = value
                .replaceAll("(?i)\\b(official|music video|video|audio|lyrics?|mv|4k|8k|1080p|720p|hi[- ]?res|flac|mp3|lossless)\\b", "")
                .replace("官方", "")
                .replace("高清", "")
                .replace("超清", "")
                .replace("无损", "")
                .replace("中英双字", "")
                .replace("双语字幕", "")
                .replace("中字", "")
                .replace("注解", "")
                .replace("歌词", "")
                .replace("动态歌词", "")
                .replaceAll("(?i)^dj(?:[a-z0-9\\u4e00-\\u9fff]*版)?$", "")
                .replaceAll("[\\s/_.,:;!?，。！？、·：；-]+", "")
                .trim();
        return TextUtils.isEmpty(stripped);
    }

    private static boolean isContainerTitle(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return false;
        if (containsAny(value, "合集", "歌单", "盘点", "精选", "排行榜", "热歌榜", "主题曲", "片尾曲", "开车听", "单曲循环", "珍藏", "收藏", "好听的")) return true;
        if (value.matches(".*\\d+\\s*(首|曲).*") && containsAny(value, "歌曲", "经典", "流行", "热歌", "老歌", "粤语", "英文")) return true;
        if (value.matches(".*\\d+\\s*(小时|分钟).*")) return true;
        return value.length() >= 28 && containsAny(value, "最好听", "喜欢", "全网", "宝藏", "值得", "前奏");
    }

    private static boolean isThemedPlaylistContainer(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return containsAny(value, "电视剧", "电视", "主题曲", "片尾曲", "片头曲", "影视", "剧集", "ost", "tvb", "atv");
    }

    private static boolean isVideoContext(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(value)) return false;
        return value.matches("(?i).*(official|music video|official video|official audio|mv|4k|8k|1080p|720p).*")
                || containsAny(value, "官方", "高清", "超清", "中英双字", "双语字幕", "注解", "歌词版", "动态歌词");
    }

    private static boolean isLikelyEpisode(String text) {
        String value = clean(text);
        if (TextUtils.isEmpty(value) || isContainerTitle(value)) return false;
        if (hasSequencePrefix(value)) return true;
        SequenceText sequence = stripSequence(value);
        return value.length() <= 72 && splitDelimited(sequence.text) != null;
    }

    private static boolean hasSequencePrefix(String text) {
        String value = clean(text);
        return value.matches("^\\s*(?:[Pp]\\s*)?(?:\\d{1,4}\\s*(?:[.．、)）:：_]|\\s+)|\\d{1,3}\\s*[-－])\\s*.+")
                || stripMediaPrefix(value).hadPrefix;
    }

    private static SequenceText stripSequence(String text) {
        String value = clean(text);
        String stripped = value.replaceFirst("^\\s*(?:[Pp]\\s*)?(?:\\d{1,4}\\s*(?:[.．、)）:：_]|\\s+)|\\d{1,3}\\s*[-－])\\s*", "");
        return new SequenceText(stripped, !stripped.equals(value));
    }

    private static SequenceText stripKnownPrefixes(String text) {
        SequenceText media = stripMediaPrefix(text);
        SequenceText sequence = stripSequence(media.text);
        return new SequenceText(sequence.text, media.hadPrefix || sequence.hadPrefix);
    }

    private static SequenceText stripMediaPrefix(String text) {
        String value = clean(text);
        String stripped = value.replaceFirst("(?i)^\\s*mp[34](?:\\s*(?:\\d{3,4}\\s*p?|[fh]d|sd))?\\s*[-_－]?\\s*", "");
        return new SequenceText(stripped, !stripped.equals(value));
    }

    private static SplitText splitDelimited(String text) {
        String value = clean(text);
        int index = singleDashIndex(value);
        if (index <= 0 || index >= value.length() - 1) return null;
        String left = cleanNamePart(value.substring(0, index));
        String right = cleanNamePart(value.substring(index + 1));
        if (left.length() < 1 || right.length() < 1) return null;
        return new SplitText(left, right);
    }

    private static int singleDashIndex(String value) {
        int index = -1;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '-' && c != '–' && c != '—' && c != '－') continue;
            index = i;
            count++;
        }
        return count == 1 ? index : -1;
    }

    private static int firstDashIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' || c == '–' || c == '—' || c == '－') return i;
        }
        return -1;
    }

    private static String stripTrailingYearSegment(String text) {
        String value = clean(text);
        value = value.replaceFirst("\\s*[-–—－]\\s*(?:19|20)\\d{2}\\s*$", "");
        value = value.replaceFirst("\\s*[-–—－]\\s*$", "");
        return value.trim();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value) && text.contains(value)) return true;
        return false;
    }

    private static boolean hasBookBracket(String text) {
        return containsAny(clean(text), "《", "》", "「", "」", "『", "』", "<", ">");
    }

    private static BookText firstBookText(String raw) {
        BookText best = null;
        best = firstBookText(raw, '《', '》', best);
        best = firstBookText(raw, '「', '」', best);
        best = firstBookText(raw, '『', '』', best);
        best = firstBookText(raw, '<', '>', best);
        return best;
    }

    private static BookText firstBookText(String raw, char open, char close, BookText best) {
        String value = clean(raw);
        int start = value.indexOf(open);
        while (start >= 0) {
            int end = value.indexOf(close, start + 1);
            if (end <= start) break;
            String text = cleanSuggestion(value.substring(start + 1, end));
            if (!TextUtils.isEmpty(text) && (best == null || start < best.start)) best = new BookText(text, start, end);
            start = value.indexOf(open, end + 1);
        }
        return best;
    }

    private static String identityText(String text) {
        return normalizeSearchText(text).toLowerCase(Locale.ROOT);
    }

    private static String normalizeArtistFromEpisode(String title, String artist) {
        String name = clean(title);
        String value = cleanArtist(artist);
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) return value;
        for (String separator : new String[]{" - ", " – ", " — ", "-"}) {
            if (value.startsWith(name + separator) && value.length() > name.length() + separator.length()) {
                return value.substring(name.length() + separator.length()).trim();
            }
            if (value.endsWith(separator + name) && value.length() > name.length() + separator.length()) {
                return value.substring(0, value.length() - name.length() - separator.length()).trim();
            }
        }
        return value;
    }

    private enum Bias {
        NEUTRAL,
        TITLE_ARTIST,
        ARTIST_TITLE
    }

    private static class Candidate {
        private final String title;
        private final String artist;
        private final int confidence;
        private final String source;
        private final String rule;
        private final String evidence;

        private Candidate(String title, String artist, int confidence, String source, String rule, String evidence) {
            this.title = cleanTitlePart(title);
            this.artist = cleanArtist(artist);
            this.confidence = confidence;
            this.source = clean(source);
            this.rule = clean(rule);
            this.evidence = clean(evidence);
        }

        private boolean isValid() {
            return !TextUtils.isEmpty(title);
        }

        private String info() {
            String value = source + ":" + rule + ":" + confidence;
            if (!TextUtils.isEmpty(evidence) && !normalizeSearchText(evidence).equals(normalizeSearchText(title))) value += ":" + evidence;
            return value;
        }
    }

    private static class SequenceText {
        private final String text;
        private final boolean hadPrefix;

        private SequenceText(String text, boolean hadPrefix) {
            this.text = text;
            this.hadPrefix = hadPrefix;
        }
    }

    private static class SplitText {
        private final String left;
        private final String right;

        private SplitText(String left, String right) {
            this.left = left;
            this.right = right;
        }
    }

    private static class BookText {
        private final String text;
        private final int start;
        private final int end;

        private BookText(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }
}
