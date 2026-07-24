@@
 data class Provider(
@@
-    val failedSearches: Int = 0
+    val failedSearches: Int = 0,
+    // Toggle whether this provider should use native TLS impersonation.
+    // Useful for testing or when native TLS causes failures for a given provider.
+    val impersonationEnabled: Boolean = true
 )
