val result = SiteAnalysis(
                providerId = providerId,
                url = normalizedUrl,
                analyzedAt = System.currentTimeMillis(),

                // Security
                securityScore = securityAnalysis.score,
                hasSSL = normalizedUrl.startsWith("https"),
                sslVersion = securityAnalysis.sslVersion,
                hasCSP = securityAnalysis.hasCSP,
                hasXFrameOptions = securityAnalysis.hasXFrameOptions,
                hasHSTS = securityAnalysis.hasHSTS,
                cookieFlags = securityAnalysis.cookieFlags,

                // DOM Structure
                domDepth = domAnalysis.maxDepth,
                totalElements = domAnalysis.totalElements,
                uniqueTags = domAnalysis.uniqueTags,
                formCount = domAnalysis.formCount,
                linkCount = domAnalysis.linkCount,
                scriptCount = domAnalysis.scriptCount,
                iframeCount = domAnalysis.iframeCount,
                imageCount = domAnalysis.imageCount,
                videoCount = domAnalysis.videoCount,

                // Patterns
                detectedPatterns = json.encodeToString(patterns),
                navigationStructure = json.encodeToString(navigationStructure),
                contentAreas = json.encodeToString(domAnalysis.contentAreas),
                searchFormSelector = patterns.find { it.type == PatternType.SEARCH_FORM }?.selector,
                searchInputSelector = findSearchInput(document),
                resultContainerSelector = patterns.find { it.type == PatternType.RESULT_LIST }?.selector,
                resultItemSelector = patterns.find { it.type == PatternType.RESULT_ITEM }?.selector,
                paginationSelector = patterns.find { it.type == PatternType.PAGINATION }?.selector,

                // Media
                videoPlayerType = mediaAnalysis.playerType,
                videoSourcePattern = mediaAnalysis.sourcePattern,
                thumbnailSelector = mediaAnalysis.thumbnailSelector,
                titleSelector = findTitleSelector(document, patterns),
                descriptionSelector = findDescriptionSelector(document),
                dateSelector = findDateSelector(document),
                ratingSelector = findRatingSelector(document),

                // API
                hasAPI = apiAnalysis.hasAPI,
                apiEndpoints = json.encodeToString(apiAnalysis.endpoints),
                apiType = apiAnalysis.type,

                // Performance
                loadTime = loadTime,
                resourceCount = document.select("script, link, img, video").size,
                totalSize = activeHtml.length.toLong(),

                // Scraping Config
                scrapingStrategy = scrapingStrategy,
                requiresJavaScript = detectJavaScriptRequirement(document),
                requiresAuth = detectAuthRequirement(document),

                // Persist public site map and config JSON for UI display and later rehydration.
                siteMapJson = SiteMapConfigAnalyzer.analyze(activeHtml, normalizedUrl).let { SiteMapConfigAnalyzer.toJson(it) },
                providerConfigJson = json.encodeToString(
                    mapOf(
                        "searchUrlTemplate" to (patterns.find { it.type == PatternType.SEARCH_FORM }?.selector ?: ""),
                        "resultItemSelector" to (patterns.find { it.type == PatternType.RESULT_ITEM }?.selector ?: ""),
                        "titleSelector" to (findTitleSelector(document, patterns) ?: ""),
                        "thumbnailSelector" to (mediaAnalysis.thumbnailSelector ?: ""),
                        "requiresJavaScript" to detectJavaScriptRequirement(document),
                        "scrapingStrategy" to scrapingStrategy.name
                    )
                ),

                // Raw data
                rawHtml = document.html().take(50000),
                headers = json.encodeToString(enrichedHeaders),
                cookies = json.encodeToString(fetchedPage.cookies)
            )