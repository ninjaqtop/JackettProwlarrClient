@Entity(tableName = "site_analysis")
@Serializable
data class SiteAnalysis(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val url: String,
    val analyzedAt: Long = System.currentTimeMillis(),

    // Security Analysis
    val securityScore: Float = 0f,
    val hasSSL: Boolean = false,
    val sslVersion: String? = null,
    val hasCSP: Boolean = false,
    val hasXFrameOptions: Boolean = false,
    val hasHSTS: Boolean = false,
    val cookieFlags: String? = null,

    // Structure Analysis
    val domDepth: Int = 0,
    val totalElements: Int = 0,
    val uniqueTags: Int = 0,
    val formCount: Int = 0,
    val linkCount: Int = 0,
    val scriptCount: Int = 0,
    val iframeCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,

    // Pattern Detection
    val detectedPatterns: String = "[]",
    val navigationStructure: String = "{}",
    val contentAreas: String = "[]",
    val searchFormSelector: String? = null,
    val searchInputSelector: String? = null,
    val resultContainerSelector: String? = null,
    val resultItemSelector: String? = null,
    val paginationSelector: String? = null,

    // Media Detection
    val videoPlayerType: String? = null,
    val videoSourcePattern: String? = null,
    val thumbnailSelector: String? = null,
    val titleSelector: String? = null,
    val descriptionSelector: String? = null,
    val dateSelector: String? = null,
    val ratingSelector: String? = null,

    // API Detection
    val hasAPI: Boolean = false,
    val apiEndpoints: String = "[]",
    val apiType: String? = null,

    // Performance Metrics
    val loadTime: Long = 0L,
    val resourceCount: Int = 0,
    val totalSize: Long = 0L,

    // Scraping Config
    val scrapingStrategy: ScrapingStrategy = ScrapingStrategy.HTML_PARSING,
    val requiresJavaScript: Boolean = false,
    val requiresAuth: Boolean = false,
    val rateLimit: Int = 10,
    val retryCount: Int = 3,

    // Stored analyzer mapping + config JSON for persistence, display, and rehydration.
    val siteMapJson: String = "{}",
    val providerConfigJson: String = "{}",

    // Raw data
    val rawHtml: String? = null,
    val headers: String = "{}",
    val cookies: String = "[]"
)