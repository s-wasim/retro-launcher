package com.retro.launcher.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CategoryMapTest {

    @Test public void socialMapsToSocial() {
        assertEquals("SOCIAL", CategoryMap.forCategory(CategoryMap.CATEGORY_SOCIAL));
    }

    @Test public void gameAudioVideoImageMapToMedia() {
        assertEquals("MEDIA", CategoryMap.forCategory(CategoryMap.CATEGORY_GAME));
        assertEquals("MEDIA", CategoryMap.forCategory(CategoryMap.CATEGORY_AUDIO));
        assertEquals("MEDIA", CategoryMap.forCategory(CategoryMap.CATEGORY_VIDEO));
        assertEquals("MEDIA", CategoryMap.forCategory(CategoryMap.CATEGORY_IMAGE));
    }

    @Test public void newsAndProductivityMapToWork() {
        assertEquals("WORK", CategoryMap.forCategory(CategoryMap.CATEGORY_NEWS));
        assertEquals("WORK", CategoryMap.forCategory(CategoryMap.CATEGORY_PRODUCTIVITY));
    }

    @Test public void mapsAndUndefinedMapToUtility() {
        assertEquals("UTILITY", CategoryMap.forCategory(CategoryMap.CATEGORY_MAPS));
        assertEquals("UTILITY", CategoryMap.forCategory(CategoryMap.CATEGORY_UNDEFINED));
    }

    @Test public void unknownIntFallsBackToUtility() {
        assertEquals("UTILITY", CategoryMap.forCategory(99));
    }
}
