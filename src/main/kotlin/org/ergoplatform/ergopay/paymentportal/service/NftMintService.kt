package org.ergoplatform.ergopay.paymentportal.service

import okhttp3.OkHttpClient
import okhttp3.Request
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ErgoId
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.Parameters
import org.ergoplatform.appkit.ReducedTransaction
import org.ergoplatform.ergopay.paymentportal.rest.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest
import kotlin.text.Charsets

@Service
class NftMintService(
    private val nodeService: NodeService,
    private val explorerApiService: ExplorerApiService,
    private val httpClient: OkHttpClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofMinutes(10)
    private val requests = ConcurrentHashMap<String, StoredMintRequest>()

    fun createMintRequest(payload: NftMintRequestPayload, request: HttpServletRequest): NftMintInitiationResponse {
        log.info("Mint request received. address={}, imageUrl={}, name={}", payload.address, payload.imageUrl, payload.name)
        validatePayload(payload)
        val baseHttpUrl = request.requestURL.toString().removeSuffix(request.requestURI)

        val buildResult = try {
            buildMintTransaction(payload)
        } catch (ex: ResponseStatusException) {
            throw ex
        } catch (ex: Exception) {
            log.error("Failed to build NFT mint transaction", ex)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.message ?: "Minting failed", ex)
        }
        val txId = UUID.randomUUID().toString()

        requests[txId] = StoredMintRequest(
            requestPayload = payload,
            baseHttpUrl = baseHttpUrl,
            createdAt = Instant.now(),
            reducedTxBase64 = buildResult.reducedTxBase64,
            tokenId = buildResult.tokenId,
        )

        val ergoPayUrl = buildErgoPayUrl(baseHttpUrl, txId)

        return NftMintInitiationResponse(
            success = true,
            txId = txId,
            tokenId = buildResult.tokenId,
            ergoPayUrl = ergoPayUrl,
        )
    }

    fun buildTransactionResponse(txId: String): ErgoPayResponse {
        val stored = requests[txId]?.takeIf { !it.isExpired(ttl) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Mint request expired or not found")

        return ErgoPayResponse().also { response ->
            response.messageSeverity = ErgoPayResponse.Severity.INFORMATION
            response.message = "Sign this transaction to mint your NFT"
            response.address = stored.requestPayload.address
            response.reducedTx = stored.reducedTxBase64
            response.replyTo = stored.baseHttpUrl + "/callback/" + txId
        }
    }

    fun recordSignedTransaction(txId: String, payload: MintCallbackPayload) {
        val stored = requests[txId] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        stored.signedTxId = payload.signedTxId
        log.info("Received signed transaction {} for NFT mint request {}", payload.signedTxId, txId)
    }

    @Scheduled(fixedDelayString = "PT5M")
    fun purgeExpired() {
        val now = Instant.now()
        requests.entries.removeIf { (_, value) -> now.isAfter(value.createdAt.plus(ttl)) }
    }

    private fun selectExplorerBoxes(address: String, requiredErg: Long): List<ExplorerBox> {
        val explorerBoxes = explorerApiService.getUnspentBoxes(address)
            .filter { it.value > 0 }

        if (explorerBoxes.isEmpty()) {
            log.warn("Explorer reports no spendable boxes for address {}", address)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Address has no spendable boxes.")
        }

        val selected = mutableListOf<ExplorerBox>()
        var total = 0L
        for (box in explorerBoxes) {
            selected += box
            total += box.value
            if (total >= requiredErg) break
        }

        if (total < requiredErg) {
            log.warn("Explorer balance below requirement. address={}, required={}, available={}, boxes={}",
                address,
                requiredErg,
                total,
                selected.map { it.boxId }
            )
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient ERG to mint NFT.")
        }
        log.debug("Selected explorer boxes {} totaling {} for address {}", selected.map { it.boxId }, total, address)
        return selected
    }

    private fun buildMintTransaction(payload: NftMintRequestPayload): MintBuildResult {
        val requiredErg = Parameters.MinChangeValue + Parameters.MinFee
        val explorerBoxes = selectExplorerBoxes(payload.address, requiredErg)
        val boxIds = explorerBoxes.map { it.boxId }

        return nodeService.getErgoClient().execute { ctx ->
            val senderAddress = Address.create(payload.address)
            val inputBoxes = ctx.getBoxesById(*boxIds.toTypedArray()).toList()

            if (inputBoxes.isEmpty()) {
                log.warn("Node returned no boxes for ids {} (address={})", boxIds, payload.address)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to load input boxes from node.")
            }

            val nodeIds = inputBoxes.map { it.id.toString() }.toSet()
            val missingFromNode = boxIds.filterNot { nodeIds.contains(it) }
            if (missingFromNode.isNotEmpty()) {
                log.warn("Node missing explorer-selected boxes {} for address {}", missingFromNode, payload.address)
            }

            val inputTotal = inputBoxes.sumOf { it.value }
            if (inputTotal < requiredErg) {
                log.warn("Insufficient ERG for NFT mint. address={}, required={}, available={}",
                    payload.address,
                    requiredErg,
                    inputTotal
                )
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient ERG to mint NFT.")
            }

            val minNftValue = Parameters.MinChangeValue

            var nftBoxValue = minNftValue
            var changeValue = inputTotal - nftBoxValue - Parameters.MinFee
            if (changeValue < 0) {
                log.warn("Negative change computed. address={}, total={}, nftBoxValue={}, fee={}",
                    payload.address,
                    inputTotal,
                    nftBoxValue,
                    Parameters.MinFee
                )
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient ERG to mint NFT.")
            }
            if (changeValue in 1 until Parameters.MinChangeValue) {
                nftBoxValue += changeValue
                changeValue = 0
            }

            val tokenIdErgo = inputBoxes.first().id
            val mintedToken = ErgoToken(tokenIdErgo, 1L)

            val metadataBytes = buildMetadataBytes(payload)
            val imageHashBytes = resolveImageHash(payload)

            val r4 = ErgoValue.of(payload.name.toByteArray(Charsets.UTF_8))
            val r5 = ErgoValue.of(payload.description.toByteArray(Charsets.UTF_8))
            val r6 = ErgoValue.of(0)
            val r7 = ErgoValue.of(byteArrayOf(0x01.toByte(), 0x01.toByte()))
            val r8 = ErgoValue.of(imageHashBytes)
            val r9 = ErgoValue.of(metadataBytes)

            val txBuilder = ctx.newTxBuilder()
            val nftBox = txBuilder.outBoxBuilder()
                .value(nftBoxValue)
                .contract(senderAddress.toErgoContract())
                .tokens(mintedToken)
                .registers(r4, r5, r6, r7, r8, r9)
                .build()

            var builder = txBuilder
                .boxesToSpend(inputBoxes)
                .outputs(nftBox)
                .fee(Parameters.MinFee)
            if (changeValue >= Parameters.MinChangeValue) {
                builder = builder.sendChangeTo(senderAddress.ergoAddress)
            }
            val unsignedTx = builder.build()

            val reducedTx: ReducedTransaction = ctx.newProverBuilder().build().reduce(unsignedTx, 0)
            val reducedTxBase64 = Base64.getUrlEncoder().encodeToString(reducedTx.toBytes())

            MintBuildResult(
                reducedTxBase64 = reducedTxBase64,
                tokenId = tokenIdErgo.toString(),
            )
        }
    }

    private fun buildErgoPayUrl(baseHttpUrl: String, txId: String): String {
        val normalizedBase = if (baseHttpUrl.endsWith("/")) baseHttpUrl.dropLast(1) else baseHttpUrl
        val httpsUrl = normalizedBase + "/tx/" + txId
        return httpsUrl.replaceFirst(Regex("^https?://"), "ergopay://")
    }

    private fun downloadResource(url: String): ByteArray {
        return try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("Resource download failed. url={}, status={}", url, response.code)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to download resource: ${response.code}")
                }
                val body = response.body ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty response when fetching resource")
                body.bytes()
            }
        } catch (ex: ResponseStatusException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("Unable to fetch resource. url={}, cause={}", url, ex.message)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to fetch resource at provided URL", ex)
        }
    }

    private fun resolveImageHash(payload: NftMintRequestPayload): ByteArray {
        val supplied = payload.imageHash?.trim()?.removePrefix("0x")
        if (!supplied.isNullOrEmpty()) {
            val hashBytes = try {
                hexStringToByteArray(supplied)
            } catch (ex: IllegalArgumentException) {
                log.warn("Invalid supplied image hash. address={}, reason={}", payload.address, ex.message)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "imageHash must be a 64-character hex string", ex)
            }
            if (hashBytes.size != 32) {
                log.warn("Supplied image hash wrong length. address={}, bytes={}", payload.address, hashBytes.size)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "imageHash must decode to 32 bytes")
            }
            log.debug("Using supplied image hash for address {}", payload.address)
            return hashBytes
        }
        val imageBytes = downloadResource(payload.imageUrl)
        return MessageDigest.getInstance("SHA-256").digest(imageBytes)
    }

    private fun buildMetadataBytes(payload: NftMintRequestPayload): ByteArray {
        val url = payload.imageUrl.trim()
        return url.toByteArray(Charsets.UTF_8)
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        if (hex.length % 2 != 0) {
            throw IllegalArgumentException("Hex string must have even length")
        }
        val normalized = hex.lowercase()
        val result = ByteArray(normalized.length / 2)
        var index = 0
        while (index < normalized.length) {
            val byteValue = normalized.substring(index, index + 2).toInt(16)
            result[index / 2] = byteValue.toByte()
            index += 2
        }
        return result
    }

    private fun validatePayload(payload: NftMintRequestPayload) {
        if (payload.address.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "address must not be blank")
        }
        if (payload.name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank")
        }
        if (payload.description.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "description must not be blank")
        }
        if (payload.imageUrl.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "imageUrl must not be blank")
        }
    }

    private data class StoredMintRequest(
        val requestPayload: NftMintRequestPayload,
        val baseHttpUrl: String,
        val createdAt: Instant,
        val reducedTxBase64: String,
        val tokenId: String,
        var signedTxId: String? = null,
    ) {
        fun isExpired(ttl: Duration): Boolean = Instant.now().isAfter(createdAt.plus(ttl))
    }

    private data class MintBuildResult(
        val reducedTxBase64: String,
        val tokenId: String,
    )

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
