package org.ergoplatform.ergopay.paymentportal.service

import com.fasterxml.jackson.databind.JsonNode
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ErgoContract
import org.ergoplatform.appkit.ErgoId
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.InputBox
import org.ergoplatform.appkit.OutBox
import org.ergoplatform.ergopay.paymentportal.rest.ErgoPayResponse
import org.ergoplatform.ergopay.paymentportal.rest.ReducedTxRegistrationResponse
import org.ergoplatform.ergopay.paymentportal.rest.ReducedTxSubmissionRequest
import org.ergoplatform.ergopay.paymentportal.rest.UnsignedTxAsset
import org.ergoplatform.ergopay.paymentportal.rest.UnsignedTxOutput
import org.ergoplatform.ergopay.paymentportal.rest.UnsignedTxPayload
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import sigmastate.Values
import sigmastate.serialization.ErgoTreeSerializer
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Service
class ReducedTxService(
    private val nodeService: NodeService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofMinutes(30)
    private val requests = ConcurrentHashMap<String, StoredReducedTx>()
    private val random = SecureRandom()
    private val alphabet = ('A'..'Z').toList()

    fun register(payload: ReducedTxSubmissionRequest, baseHttpUrl: String): ReducedTxRegistrationResponse {
        validatePayload(payload)
        val requestId = generateRequestId()
        val reducedTxBase64 = when {
            !payload.reducedTx.isNullOrMissing() -> extractReducedTx(payload.reducedTx)
            payload.unsignedTx != null -> buildReducedTransaction(payload)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Either reducedTx or unsignedTx must be provided")
        }

        val stored = StoredReducedTx(
            reducedTx = reducedTxBase64,
            address = payload.address.trim(),
            message = payload.message?.takeIf { it.isNotBlank() },
            severity = parseSeverity(payload.messageSeverity),
            replyTo = payload.replyTo?.takeIf { it.isNotBlank() },
            createdAt = Instant.now(),
        )
        requests[requestId] = stored
        val ergoPayUrl = buildErgoPayUrl(baseHttpUrl, requestId)
        log.info("Stored reduced transaction request {}", requestId)
        return ReducedTxRegistrationResponse(ergoPayUrl, requestId)
    }

    fun buildResponse(requestId: String): ErgoPayResponse {
        val stored = requests[requestId]
            ?.takeIf { !it.isExpired(ttl) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Reduced transaction request expired or not found")

        return ErgoPayResponse().apply {
            message = stored.message ?: "Please review and sign the transaction."
            messageSeverity = stored.severity ?: ErgoPayResponse.Severity.INFORMATION
            address = stored.address
            reducedTx = stored.reducedTx
            replyTo = stored.replyTo
        }
    }

    @Scheduled(fixedDelayString = "PT5M")
    fun purgeExpired() {
        val now = Instant.now()
        val removed = requests.entries.removeIf { (_, stored) -> now.isAfter(stored.createdAt.plus(ttl)) }
        if (removed) {
            log.debug("Purged expired reduced transaction requests")
        }
    }

    private fun validatePayload(payload: ReducedTxSubmissionRequest) {
        if (payload.address.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "address must not be blank")
        }
        if (payload.reducedTx.isNullOrMissing() && payload.unsignedTx == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Either reducedTx or unsignedTx must be provided")
        }
        payload.unsignedTx?.let { unsigned ->
            if (unsigned.inputs.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unsignedTx.inputs must not be empty")
            }
            if (unsigned.outputs.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unsignedTx.outputs must not be empty")
            }
        }
    }

    private fun buildReducedTransaction(payload: ReducedTxSubmissionRequest): String {
        val unsignedPayload = payload.unsignedTx
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unsignedTx must not be null")

        return nodeService.getErgoClient().execute { ctx ->
            val inputIds = unsignedPayload.inputs.map { it.boxId }
            val inputBoxes = ctx.getBoxesById(*inputIds.toTypedArray()).toList()
            if (inputBoxes.size != inputIds.size) {
                val found = inputBoxes.map { it.id.toString() }.toSet()
                val missing = inputIds.filterNot { found.contains(it) }
                log.warn("Failed to load input boxes {}", missing)
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to load input boxes: $missing")
            }

            val dataInputIds = unsignedPayload.dataInputs.map { it.boxId }
            val dataInputBoxes: List<InputBox> = if (dataInputIds.isNotEmpty()) {
                val boxes = ctx.getBoxesById(*dataInputIds.toTypedArray()).toList()
                if (boxes.size != dataInputIds.size) {
                    val found = boxes.map { it.id.toString() }.toSet()
                    val missing = dataInputIds.filterNot { found.contains(it) }
                    log.warn("Failed to load data input boxes {}", missing)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to load data input boxes: $missing")
                }
                boxes
            } else emptyList()

            val txBuilder = ctx.newTxBuilder()
            val outBoxes: List<OutBox> = unsignedPayload.outputs.map { output ->
                buildOutBox(txBuilder, ctx, unsignedPayload, output)
            }

            var builder = txBuilder
                .boxesToSpend(inputBoxes)
                .outputs(*outBoxes.toTypedArray())

            if (dataInputBoxes.isNotEmpty()) {
                builder = builder.withDataInputs(dataInputBoxes)
            }

            unsignedPayload.fee?.let { builder = builder.fee(it) }
            unsignedPayload.changeAddress?.let {
                builder = builder.sendChangeTo(Address.create(it).ergoAddress)
            }

            val unsignedTx = builder.build()
            val reducedTx = ctx.newProverBuilder().build().reduce(unsignedTx, 0)
            Base64.getUrlEncoder().encodeToString(reducedTx.toBytes())
        }
    }

    private fun buildOutBox(
        txBuilder: org.ergoplatform.appkit.UnsignedTransactionBuilder,
        ctx: org.ergoplatform.appkit.BlockchainContext,
        unsignedPayload: UnsignedTxPayload,
        output: UnsignedTxOutput,
    ): OutBox {
        val value = output.value.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Output value must be a number: ${output.value}")
        val contract = resolveContract(ctx, output)

        val builder = txBuilder.outBoxBuilder()
            .value(value)
            .contract(contract)

        val creationHeight = output.creationHeight ?: unsignedPayload.creationHeight ?: ctx.height
        builder.creationHeight(creationHeight)

        if (output.assets.isNotEmpty()) {
            val tokens = output.assets.map(::toErgoToken).toTypedArray()
            builder.tokens(*tokens)
        }

        output.additionalRegisters?.let { registers ->
            if (registers.isNotEmpty()) {
                builder.registers(*parseRegisters(registers))
            }
        }

        return builder.build()
    }

    private fun toErgoToken(asset: UnsignedTxAsset): ErgoToken {
        val amount = asset.amount.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid token amount: ${asset.amount}")
        return ErgoToken(ErgoId.create(asset.tokenId), amount)
    }

    private fun parseRegisters(registers: Map<String, String>): Array<ErgoValue<*>> {
        return registers.entries
            .map { entry ->
                val index = parseRegisterIndex(entry.key)
                index to ErgoValue.fromHex(entry.value)
            }
            .sortedBy { it.first }
            .map { it.second }
            .toTypedArray()
    }

    private fun parseRegisterIndex(register: String): Int {
        if (!register.startsWith("R", ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid register key: $register")
        }
        return register.substring(1).toIntOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid register index: $register")
    }

    private fun resolveContract(ctx: org.ergoplatform.appkit.BlockchainContext, output: UnsignedTxOutput): ErgoContract {
        output.address?.takeIf { it.isNotBlank() }?.let {
            return Address.create(it).toErgoContract()
        }
        val ergoTreeHex = output.ergoTree?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Output must contain address or ergoTree")
        val ergoTreeBytes = try {
            Hex.decode(ergoTreeHex)
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ergoTree hex value", ex)
        }
        val ergoTree: Values.ErgoTree = ErgoTreeSerializer.DefaultSerializer().deserializeErgoTree(ergoTreeBytes)
        return ctx.newContract(ergoTree)
    }

    private fun extractReducedTx(node: JsonNode?): String {
        val actual = node ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "reducedTx must not be null")
        return if (actual.isTextual) actual.asText().trim() else actual.toString()
    }

    private fun parseSeverity(raw: String?): ErgoPayResponse.Severity? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            ErgoPayResponse.Severity.valueOf(value.uppercase())
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid messageSeverity value: $value", ex)
        }
    }

    private fun generateRequestId(length: Int = 10): String = buildString(length) {
        repeat(length) {
            append(alphabet[random.nextInt(alphabet.size)])
        }
    }

    private fun buildErgoPayUrl(baseHttpUrl: String, requestId: String): String {
        val normalizedBase = if (baseHttpUrl.endsWith('/')) baseHttpUrl.dropLast(1) else baseHttpUrl
        val httpsUrl = "$normalizedBase/payment/sign/$requestId"
        val ergoPayUrl = httpsUrl.replaceFirst(Regex("^https?://"), "ergopay://")
        return "$ergoPayUrl?sender=#P2PK_ADDRESS#"
    }

    private fun JsonNode?.isNullOrMissing(): Boolean = this == null || this.isNull || this.isMissingNode

    private data class StoredReducedTx(
        val reducedTx: String,
        val address: String,
        val message: String?,
        val severity: ErgoPayResponse.Severity?,
        val replyTo: String?,
        val createdAt: Instant,
    ) {
        fun isExpired(ttl: Duration): Boolean = Instant.now().isAfter(createdAt.plus(ttl))
    }
}
