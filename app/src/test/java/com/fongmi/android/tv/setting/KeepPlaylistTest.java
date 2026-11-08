package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeepPlaylistTest {

    private static final String A = "site@@@vod-a";
    private static final String B = "site@@@vod-b";
    private static final String C = "site@@@vod-c";

    private KeepPlaylist model() {
        return KeepPlaylist.empty();
    }

    @Test
    public void newModelIsEmpty() {
        assertTrue(model().isEmpty());
        assertEquals(0, model().getPlaylists().size());
        assertEquals(KeepPlaylist.UNFILED, model().playlistOf(A));
    }

    @Test
    public void createPlaylistAssignsIdAndName() {
        KeepPlaylist model = model();
        String id = model.createPlaylist("  我的  片单 ", 1000L);
        assertNotNull(id);
        assertEquals(1, model.getPlaylists().size());
        assertEquals("我的 片单", model.getPlaylists().get(0).getName());
        assertFalse(model.isEmpty());
    }

    @Test
    public void createPlaylistRejectsBlankDuplicateAndOverflow() {
        KeepPlaylist model = model();
        assertNull(model.createPlaylist("   ", 1L));
        assertNull(model.createPlaylist("", 1L));
        assertNotNull(model.createPlaylist("片单", 1L));
        assertNull(model.createPlaylist("片单", 2L));
        assertNull(model.createPlaylist("  片单  ", 3L));
    }

    @Test
    public void renamePlaylistRejectsDuplicateAndKeepsOriginal() {
        KeepPlaylist model = model();
        String first = model.createPlaylist("一", 1L);
        model.createPlaylist("二", 2L);
        assertTrue(model.renamePlaylist(first, "新名字"));
        assertEquals("新名字", model.findPlaylist(first).getName());
        assertFalse(model.renamePlaylist(first, "二"));
        assertEquals("新名字", model.findPlaylist(first).getName());
        assertFalse(model.renamePlaylist("missing", "x"));
        assertFalse(model.renamePlaylist(first, "  "));
    }

    @Test
    public void moveFailsAtomicallyForUnknownPlaylist() {
        KeepPlaylist model = model();
        model.setTags(A, Collections.singletonList("标签"));
        assertFalse(model.move(Collections.singletonList(A), "not-exist"));
        assertEquals(KeepPlaylist.UNFILED, model.playlistOf(A));
        assertEquals(1, model.tagsOf(A).size());
    }

    @Test
    public void moveToUnfiledKeepsTags() {
        KeepPlaylist model = model();
        String id = model.createPlaylist("片单", 1L);
        model.move(Arrays.asList(A, B), id);
        model.setTags(A, Collections.singletonList("动作"));
        assertEquals(2, model.countOf(id));
        model.move(Collections.singletonList(A), KeepPlaylist.UNFILED);
        assertEquals(1, model.countOf(id));
        assertEquals(KeepPlaylist.UNFILED, model.playlistOf(A));
        assertEquals(Collections.singletonList("动作"), model.tagsOf(A));
    }

    @Test
    public void deletingPlaylistKeepsItemsAsUnfiled() {
        KeepPlaylist model = model();
        String id = model.createPlaylist("片单", 1L);
        model.move(Arrays.asList(A, B), id);
        assertTrue(model.deletePlaylist(id));
        assertEquals(0, model.getPlaylists().size());
        assertEquals(KeepPlaylist.UNFILED, model.playlistOf(A));
        assertEquals(KeepPlaylist.UNFILED, model.playlistOf(B));
        assertFalse(model.deletePlaylist(id));
    }

    @Test
    public void entryWithNeitherPlaylistNorTagIsDropped() {
        KeepPlaylist model = model();
        model.move(Collections.singletonList(A), KeepPlaylist.UNFILED);
        assertTrue(model.isEmpty());
    }

    @Test
    public void tagsAreDeduplicatedCaseInsensitivelyAndTrimmed() {
        KeepPlaylist model = model();
        assertTrue(model.setTags(A, Arrays.asList(" 动作 ", "动作", "ACTION", "action", "  ", null)));
        assertEquals(Arrays.asList("动作", "ACTION"), model.tagsOf(A));
    }

    @Test
    public void tagLengthAndCountAreCapped() {
        List<String> tags = new java.util.ArrayList<>();
        for (int i = 0; i < KeepPlaylist.MAX_TAGS_PER_ITEM + 5; i++) tags.add("t" + i);
        assertEquals(KeepPlaylist.MAX_TAGS_PER_ITEM, KeepPlaylist.sanitizeTags(tags).size());
        String longTag = "x".repeat(KeepPlaylist.MAX_TAG_LENGTH + 20);
        assertEquals(KeepPlaylist.MAX_TAG_LENGTH, KeepPlaylist.normalizeTag(longTag).length());
    }

    @Test
    public void allTagsIsSortedAndDeduplicated() {
        KeepPlaylist model = model();
        model.setTags(A, Arrays.asList("beta", "Alpha"));
        model.setTags(B, Collections.singletonList("alpha"));
        assertEquals(Arrays.asList("Alpha", "beta"), model.allTags());
    }

    @Test
    public void keysWithTagMatchesIgnoringCase() {
        KeepPlaylist model = model();
        model.setTags(A, Collections.singletonList("动作"));
        model.setTags(B, Collections.singletonList("喜剧"));
        assertEquals(Collections.singletonList(A), model.keysWithTag(" 动作 "));
        assertEquals(Collections.singletonList(A), model.keysWithTag("动作"));
        assertTrue(model.keysWithTag("不存在").isEmpty());
        assertTrue(model.keysWithTag("").isEmpty());
    }

    @Test
    public void removeKeysClearsPlaylistAndTags() {
        KeepPlaylist model = model();
        String id = model.createPlaylist("片单", 1L);
        model.move(Collections.singletonList(A), id);
        model.setTags(A, Collections.singletonList("标签"));
        model.removeKeys(Arrays.asList(A, "never-existed"));
        assertEquals(0, model.countOf(id));
        assertTrue(model.tagsOf(A).isEmpty());
        assertEquals(1, model.getPlaylists().size());
    }

    @Test
    public void jsonRoundTripPreservesEverything() {
        KeepPlaylist model = model();
        String id = model.createPlaylist("片单", 1L);
        model.move(Arrays.asList(A, B), id);
        model.setTags(A, Arrays.asList("动作", "高分"));
        KeepPlaylist parsed = KeepPlaylist.fromJson(model.toJson());
        assertEquals(1, parsed.getPlaylists().size());
        assertEquals("片单", parsed.getPlaylists().get(0).getName());
        assertEquals(2, parsed.countOf(parsed.getPlaylists().get(0).getId()));
        assertEquals(Arrays.asList("动作", "高分"), parsed.tagsOf(A));
    }

    @Test
    public void jsonSurvivesGarbageInput() {
        assertTrue(KeepPlaylist.fromJson(null).isEmpty());
        assertTrue(KeepPlaylist.fromJson("").isEmpty());
        assertTrue(KeepPlaylist.fromJson("   ").isEmpty());
        assertTrue(KeepPlaylist.fromJson("{").isEmpty());
        assertTrue(KeepPlaylist.fromJson("null").isEmpty());
        assertTrue(KeepPlaylist.fromJson("[]").isEmpty());
        assertTrue(KeepPlaylist.fromJson("{\"playlists\":\"nope\"}").isEmpty());
    }

    @Test
    public void jsonDropsEntriesPointingAtMissingPlaylist() {
        String json = "{\"version\":1,\"playlists\":[],\"entries\":[{\"key\":\"" + A + "\",\"playlistId\":\"ghost\",\"tags\":[\"t\"]}]}";
        KeepPlaylist parsed = KeepPlaylist.fromJson(json);
        assertEquals(KeepPlaylist.UNFILED, parsed.playlistOf(A));
        assertEquals(Collections.singletonList("t"), parsed.tagsOf(A));
    }

    @Test
    public void jsonDropsDuplicateIdsAndKeys() {
        String json = "{\"version\":1,\"playlists\":[{\"id\":\"p1\",\"name\":\"一\"},{\"id\":\"p1\",\"name\":\"二\"}],"
                + "\"entries\":[{\"key\":\"" + A + "\",\"playlistId\":\"p1\",\"tags\":[\"x\"]},{\"key\":\"" + A + "\",\"playlistId\":\"p1\",\"tags\":[\"y\"]}]}";
        KeepPlaylist parsed = KeepPlaylist.fromJson(json);
        assertEquals(1, parsed.getPlaylists().size());
        assertEquals(1, parsed.allKeys().size());
    }

    @Test
    public void jsonDropsPlaylistWithoutNameOrId() {
        String json = "{\"version\":1,\"playlists\":[{\"id\":\"\",\"name\":\"一\"},{\"id\":\"p2\",\"name\":\"  \"},{\"id\":\"p3\",\"name\":\"三\"}],\"entries\":[]}";
        KeepPlaylist parsed = KeepPlaylist.fromJson(json);
        assertEquals(1, parsed.getPlaylists().size());
        assertEquals("三", parsed.getPlaylists().get(0).getName());
    }

    @Test
    public void playlistCountIsCapped() {
        KeepPlaylist model = model();
        for (int i = 0; i < KeepPlaylist.MAX_PLAYLISTS + 10; i++) model.createPlaylist("片单" + i, i);
        assertEquals(KeepPlaylist.MAX_PLAYLISTS, model.getPlaylists().size());
    }

    @Test
    public void nameIsCappedAtMaxLength() {
        String longName = "名".repeat(KeepPlaylist.MAX_NAME_LENGTH + 30);
        assertEquals(KeepPlaylist.MAX_NAME_LENGTH, KeepPlaylist.normalizeName(longName).length());
    }

    @Test
    public void moveAcceptsDuplicateAndNullKeysSafely() {
        KeepPlaylist model = model();
        String id = model.createPlaylist("片单", 1L);
        assertTrue(model.move(Arrays.asList(A, A, null, "  "), id));
        assertEquals(1, model.countOf(id));
        assertTrue(model.move(null, id));
    }
}
