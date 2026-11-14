package io.github.miaiplus;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersonaProfilesTest {
    private static final class Storage {
        final Map<String, String> values = new HashMap<>();
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getString" -> values.getOrDefault((String) args[0], (String) args[1]);
                case "edit" -> editor();
                default -> throw new AssertionError(method.getName());
            });

        SharedPreferences.Editor editor() {
            Map<String, String> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "putString" -> { pending.put((String) args[0], (String) args[1]); yield proxy; }
                    case "commit" -> { values.putAll(pending); yield true; }
                    default -> throw new AssertionError(method.getName());
                });
        }

        void save(PersonaProfiles.Profile profile, boolean delete) {
            var editor = prefs.edit();
            new PersonaProfiles(prefs).write(editor, profile, delete);
            editor.commit();
        }
    }

    private static PersonaProfiles.Profile persona(String id, String name, String text) {
        return new PersonaProfiles.Profile(id, name, text);
    }

    @Test public void freshStoreKeepsASingleEmptyDefaultPersonaAndLeavesTheFlatSnapshotEmpty() {
        Storage storage = new Storage();
        PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
        assertEquals(1, profiles.profiles.size());
        assertEquals("默认人设", profiles.active().name);
        assertEquals("", profiles.active().text);
        storage.save(profiles.active(), false);
        assertEquals("", storage.values.get(Config.CUSTOM_PROMPT_TEXT_KEY));
        assertEquals(profiles.active().encode(), new PersonaProfiles(storage.prefs).active().encode());
    }

    @Test public void missingStoreMigratesTheFlatPromptTextIntoTheDefaultPersona() {
        Storage storage = new Storage();
        storage.values.put(Config.CUSTOM_PROMPT_TEXT_KEY, "已保存的提示词");
        PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
        assertEquals("默认人设", profiles.active().name);
        assertEquals("已保存的提示词", profiles.active().text);
    }

    @Test public void blankAndOverlongNamesFailValidationAndDuplicatesLeaveStorageUntouched() {
        for (String name : new String[]{"", "   ", "一二三四五六七八九十一二三四五六七八九十一"}) {
            try { persona("b", name, "新内容").validated(); fail("Accepted: " + name); }
            catch (IllegalArgumentException expected) { }
        }
        try { persona("b", "", "新内容").validated(); fail("Blank name accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("请填写人设名称", expected.getMessage()); }
        Storage storage = new Storage();
        storage.save(persona("a", "人设甲", "原文"), true);
        Map<String, String> before = new HashMap<>(storage.values);
        try { storage.save(persona("b", " 人设甲 ", "新内容"), false); fail("Duplicate accepted"); }
        catch (IllegalArgumentException expected) {
            assertEquals("人设名称已存在", expected.getMessage());
            assertEquals(before, storage.values);
        }
        PersonaProfiles.Profile renamed = persona("a", "一二三四五六七八九十一二三四五六七八九十", "原文").validated();
        assertEquals(20, renamed.name.length());
        storage.save(renamed, false);
        PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
        assertEquals(1, profiles.profiles.size());
        assertEquals("一二三四五六七八九十一二三四五六七八九十", profiles.active().name);
    }

    @Test public void validationRequiresPromptTextAndTruncatesItAtFourThousandCharacters() {
        try { persona("id", "名称", "   ").validated(); fail("Blank text accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("请填写提示词内容", expected.getMessage()); }
        String oversized = "字".repeat(Config.MAX_CUSTOM_PROMPT_CHARS + 50);
        PersonaProfiles.Profile validated = persona("id", " 名称 ", oversized).validated();
        assertEquals("名称", validated.name);
        assertEquals(Config.MAX_CUSTOM_PROMPT_CHARS, validated.text.length());
        assertTrue(oversized.startsWith(validated.text));
    }

    @Test public void switchingWritesTheActiveProfileTextIntoTheFlatSnapshotAndKeepsTheOtherProfile() {
        Storage storage = new Storage();
        var first = persona("a", "人设甲", "甲的提示词");
        var second = persona("b", "人设乙", "乙的提示词");
        storage.save(first, true);
        storage.save(second, false);
        assertEquals(2, new PersonaProfiles(storage.prefs).profiles.size());
        storage.save(new PersonaProfiles(storage.prefs).find(first.id), false);
        PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
        assertEquals(first.id, profiles.activeId);
        assertEquals("甲的提示词", storage.values.get(Config.CUSTOM_PROMPT_TEXT_KEY));
        assertEquals("乙的提示词", profiles.find(second.id).text);
    }

    @Test public void deletingTheActiveProfileSelectsTheRemainingOneAndDropsItsText() {
        Storage storage = new Storage();
        var first = persona("a", "甲", "甲的内容");
        var second = persona("b", "乙", "乙的内容");
        storage.save(first, true);
        storage.save(second, false);
        storage.save(first, false);
        storage.save(second, true);
        PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
        assertEquals(1, profiles.profiles.size());
        assertEquals(second.id, profiles.activeId);
        assertEquals("乙的内容", storage.values.get(Config.CUSTOM_PROMPT_TEXT_KEY));
        assertFalse(storage.values.toString().contains("甲的内容"));
    }

    @Test public void missingOrDeletedActiveIdentitySelectsTheFirstStoredPersona() {
        for (String active : new String[]{null, "deleted"}) {
            Storage storage = new Storage();
            storage.save(persona("a", "甲", "甲的内容"), true);
            storage.save(persona("b", "乙", "乙的内容"), false);
            if (active == null) storage.values.remove("activePersonaProfile");
            else storage.values.put("activePersonaProfile", active);

            PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
            assertEquals("a", profiles.activeId);
            assertEquals("甲的内容", profiles.active().text);
            assertEquals(2, profiles.profiles.size());
        }
    }

    @Test public void corruptJsonFallsBackToAnEmptyDefaultPersonaInsteadOfThrowing() {
        for (String broken : new String[]{"broken", "[]", "[\"not-json\"]",
            "[{\"id\":\"\",\"name\":\"有名\",\"text\":\"\"}]",
            "[{\"id\":\"a\",\"name\":\"甲\",\"text\":\"\"},{\"id\":\"a\",\"name\":\"乙\",\"text\":\"\"}]",
            "[{\"id\":\"a\",\"name\":\"同名\",\"text\":\"\"},{\"id\":\"b\",\"name\":\"同名\",\"text\":\"\"}]"}) {
            Storage storage = new Storage();
            storage.values.put("personaProfiles", broken);
            storage.values.put(Config.CUSTOM_PROMPT_TEXT_KEY, "旧快照");
            PersonaProfiles profiles = new PersonaProfiles(storage.prefs);
            assertEquals(broken, 1, profiles.profiles.size());
            assertEquals(broken, "默认人设", profiles.active().name);
            assertEquals(broken, "", profiles.active().text);
        }
    }
}
