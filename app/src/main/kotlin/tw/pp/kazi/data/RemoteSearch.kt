package tw.pp.kazi.data

data class RemoteSearchRequest(
    val keyword: String,
    val siteIds: List<Long>,
    val timestamp: Long = System.currentTimeMillis(),
)
