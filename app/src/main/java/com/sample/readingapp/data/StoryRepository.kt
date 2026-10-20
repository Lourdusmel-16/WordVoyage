package com.sample.readingapp.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stories now come from a JSON file instead of Firestore:
 *  - app/src/main/assets/stories.json            (the built-in story library)
 *  - <app files>/added_stories.json              (stories added by the admin inside the app)
 *
 * Reading progress is still saved per user in Firebase, using each story's "id" as the key,
 * so every story in stories.json needs a unique, permanent id.
 */
object StoryRepository {
    private const val ASSET_FILE = "stories.json"
    private const val ADDED_FILE = "added_stories.json"
    private val gson = Gson()

    /** Strictly retrieves stories belonging ONLY to the user's AgeGroup */
    suspend fun fetchStoriesByAgeGroup(context: Context, ageGroup: AgeGroup): List<Story> =
        withContext(Dispatchers.IO) {
            try {
                val all = loadAllStories(context)
                val groupStories = all.filter { it.ageGroup.equals(ageGroup.name, ignoreCase = true) }

                if (groupStories.isNotEmpty()) {
                    groupStories.sortedBy { it.order }
                } else {
                    // Secondary check: look for stories explicitly matching the grade level badge
                    all.filter { story ->
                        story.gradeLevel.contains(ageGroup.badge, ignoreCase = true) ||
                                story.gradeLevel.contains(ageGroup.title, ignoreCase = true)
                    }.sortedBy { it.order }
                }
            } catch (e: Exception) {
                Log.e("StoryRepository", "Error loading stories: ${e.message}")
                emptyList()
            }
        }

    /** Built-in stories plus admin-added ones (an added story replaces a built-in one with the same id). */
    private fun loadAllStories(context: Context): List<Story> {
        val merged = LinkedHashMap<String, Story>()
        try {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            parseStories(json).forEach { merged[it.id] = it }
        } catch (e: Exception) {
            Log.e("StoryRepository", "Could not read $ASSET_FILE: ${e.message}")
        }
        readAddedStories(context).forEach { merged[it.id] = it }
        return merged.values.sortedBy { it.order }
    }

    /** Accepts either a JSON array of stories, or an object like { "stories": [ ... ] }. */
    fun parseStories(json: String): List<Story> {
        val root = JsonParser.parseString(json)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("stories") -> root.asJsonObject.getAsJsonArray("stories")
            else -> return emptyList()
        }
        val result = mutableListOf<Story>()
        array.forEachIndexed { index, element ->
            try {
                val parsed = gson.fromJson(element, Story::class.java) ?: return@forEachIndexed
                var story = parsed
                if (story.id.isBlank()) {
                    story = story.copy(id = "story_" + slug(story.title).ifBlank { index.toString() })
                }
                if (element.isJsonObject && !element.asJsonObject.has("ageGroup")) {
                    story = story.copy(ageGroup = AgeGroup.fromString(story.gradeLevel).name)
                }
                if (story.title.isNotBlank() && story.screens.isNotEmpty()) result.add(story)
            } catch (e: Exception) {
                Log.e("StoryRepository", "Skipping an invalid story: ${e.message}")
            }
        }
        return result
    }

    private fun slug(text: String): String =
        text.lowercase().replace("[^a-z0-9]+".toRegex(), "_").trim('_')

    // ---------------- stories added by the admin on this device ----------------
    private fun addedFile(context: Context) = File(context.filesDir, ADDED_FILE)

    private fun readAddedStories(context: Context): List<Story> {
        return try {
            val f = addedFile(context)
            if (f.exists()) parseStories(f.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAddedStory(context: Context, story: Story) {
        val current = readAddedStories(context).filter { it.id != story.id } + story
        addedFile(context).writeText(gson.toJson(current))
    }
}
