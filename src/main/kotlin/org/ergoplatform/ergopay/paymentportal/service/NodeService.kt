package org.ergoplatform.ergopay.paymentportal.service

import okhttp3.OkHttpClient
import org.ergoplatform.appkit.ErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.RestApiErgoClient
import org.ergoplatform.restapi.client.ErgoTransactionOutput
import org.ergoplatform.restapi.client.UtxoApi
import org.springframework.stereotype.Service
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

@Service
class NodeService(
    private val okHttpClient: OkHttpClient
) {
    private val nodeApiUrl = System.getenv("ERGO_NODE_API_URL") ?: "http://127.0.0.1:9053/"
    private val nodeApiKey = System.getenv("ERGO_NODE_API_KEY") ?: ""

    fun getErgoClient(): ErgoClient {
        return RestApiErgoClient.createWithHttpClientBuilder(
            nodeApiUrl,
            NetworkType.MAINNET,
            nodeApiKey,
            RestApiErgoClient.defaultMainnetExplorerUrl,
            okHttpClient.newBuilder()
        )
    }

    fun getBoxDataById(boxId: String): ErgoTransactionOutput? {
        return try {
            val utxoApi = getNodeRetrofit().create(UtxoApi::class.java)
            val apiCall = utxoApi.getBoxById(boxId).execute()

            apiCall.body()
        } catch (t: Throwable) {
            null
        }
    }

    private fun getNodeRetrofit() = Retrofit.Builder()
        .baseUrl(nodeApiUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient.newBuilder().connectTimeout(5, TimeUnit.SECONDS).build())
        .build()
}
