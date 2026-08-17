package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.ProviderSearchResults
import com.aggregatorx.app.data.model.ProviderSearchStatus
import com.aggregatorx.app.data.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultFilterTest {
    @Test
    fun `falls back to structurally valid results when no query terms match`() {
        val results = listOf(
            SearchResult(
                providerId = "p1",
                providerName = "Probe",
                title = "Latest release",
                url = "https://example.com/video/1",
                description = "A popular title",
                relevanceScore = 20f
            ),
            SearchResult(
                providerId = "p1",
                providerName = "Probe",
                title = "Another item",
                url = "https://example.com/movie/2",
                description = "Another release",
                relevanceScore = 15f
            )
        )

        val filtered = SearchResultFilter.filterResultsForDisplay(results, "batman returns", null)

        assertFalse(filtered.isEmpty())
        assertTrue(filtered.size <= 10)
        assertTrue(filtered.any { it.title == "Latest release" })
    }

    @Test
    fun `provider status does not crash when error message is null`() {
        val providerResult = ProviderSearchResults(
            provider = com.aggregatorx.app.data.model.Provider(
                id = "p-1",
                name = "Test Provider",
                url = "https://example.com",
                baseUrl = "https://example.com"
            ),
            results = emptyList(),
            searchTime = 0L,
            success = false,
            errorMessage = null
        )

        assertEquals(ProviderSearchStatus.FAILED, providerResult.status)
    }
}
