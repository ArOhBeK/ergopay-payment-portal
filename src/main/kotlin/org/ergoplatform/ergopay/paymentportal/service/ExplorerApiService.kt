package org.ergoplatform.ergopay.paymentportal.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ergoplatform.appkit.RestApiErgoClient
import org.ergoplatform.explorer.client.DefaultApi
import org.springframework.stereotype.Service
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

@Service
class ExplorerApiService(private val okHttpClient: OkHttpClient) {
    private val mapper = jacksonObjectMapper()
    val timeout = 30L // 30 seconds since Explorer can be slooooow

    private val api by lazy {
        buildExplorerApi(RestApiErgoClient.defaultMainnetExplorerUrl)
    }

    private fun buildExplorerApi(url: String) = Retrofit.Builder()
        .baseUrl(url)
        .addConverterFactory(GsonConverterFactory.create())
        .client(
            okHttpClient.newBuilder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS).build()
        )
        .build()
        .create(DefaultApi::class.java)

    private fun <T> wrapCall(call: () -> Call<T>): T {
        val explorerCall = call().execute()

        if (!explorerCall.isSuccessful)
            throw IOException("Error calling Explorer: ${explorerCall.errorBody()}")

        return explorerCall.body()!!
    }

    fun getTransactionInfo(txId: String) =
        wrapCall {
            api.getApiV1TransactionsP1(txId)
        }

    fun getBoxInformation(boxId: String) =
        wrapCall {
            api.getApiV1BoxesP1(boxId)
        }

    fun getUnspentBoxes(address: String, limit: Int = 50): List<ExplorerBox> {
        val url = "https://api.ergo.aap.cornell.edu/api/v1/boxes/unspent/unconfirmed/byAddress/$address?limit=$limit&offset=0"
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Explorer API error ${response.code}: $bodyString")
            }
            if (bodyString.isEmpty()) {
                throw IOException("Empty Explorer response")
            }
            return try {
                mapper.readValue(bodyString, object : TypeReference<List<ExplorerBox>>() {})
            } catch (ex: Exception) {
                mapper.readValue(bodyString, ExplorerBoxesResponse::class.java).items
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExplorerBoxesResponse(val items: List<ExplorerBox>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExplorerBox(
    val boxId: String,
    val value: Long,
    val assets: List<ExplorerAsset> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExplorerAsset(
    val tokenId: String,
    val amount: Long,
)
