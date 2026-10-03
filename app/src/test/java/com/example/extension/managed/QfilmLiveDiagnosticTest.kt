package com.example.extension.managed

import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.scraper.QfilmScraper
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.URLEncoder

class QfilmLiveDiagnosticTest {

    @Test
    fun executeLiveQfilmDiagnostic() = runBlocking {
        println("=== [QFILM_DIAG] LIVE TEST STARTING ===")
        val scraper = QfilmScraper()
        val extension = ManagedExtension(
            id = "qfilm",
            name = "كيو فيلم",
            baseUrl = "https://a.qfilm.tv",
            scraperKey = "qfilm"
        )

        val query = "محمود التاني"
        println("[QFILM_DIAG] 01_EXTENSION_SELECTED id=${extension.id}, baseUrl=${extension.baseUrl}")
        println("[QFILM_DIAG] 02_SEARCH_REQUEST_STARTED query='$query'")

        try {
            val result = scraper.search(extension, SearchRequest(query = query))
            if (result.isSuccess) {
                val data = result.getOrThrow()
                println("[QFILM_DIAG] 07_SEARCH_RESULTS_COUNT count=${data.items.size}")
                data.items.take(3).forEachIndexed { i, item ->
                    println("[QFILM_DIAG] RESULT #$i: title='${item.title}', url='${item.url}', poster='${item.posterUrl}'")
                }
            } else {
                val err = result.exceptionOrNull()
                println("[QFILM_DIAG] SEARCH_FAILURE: type=${err?.javaClass?.name}, message=${err?.message}")
                err?.printStackTrace()
            }
        } catch (e: Exception) {
            println("[QFILM_DIAG] UNCAUGHT_EXCEPTION: ${e.message}")
            e.printStackTrace()
        }
        println("=== [QFILM_DIAG] LIVE TEST FINISHED ===")
    }
}
