package org.ergoplatform.ergopay.paymentportal.rest

import com.fasterxml.jackson.databind.JsonNode

/**
 * Request payload for registering a reduced transaction.
 * Either [reducedTx] (already serialized reduced transaction as Base64 or JSON) or [unsignedTx]
 * must be provided. When [unsignedTx] is supplied the server reconstructs and reduces the
 * transaction on behalf of the caller.
 */
data class ReducedTxSubmissionRequest(
    val address: String,
    val message: String? = null,
    val messageSeverity: String? = null,
    val replyTo: String? = null,
    val reducedTx: JsonNode? = null,
    val unsignedTx: UnsignedTxPayload? = null,
)

data class ReducedTxRegistrationResponse(
    val url: String,
    val requestId: String,
)

data class UnsignedTxPayload(
    val creationHeight: Int? = null,
    val fee: Long? = null,
    val changeAddress: String? = null,
    val inputs: List<UnsignedTxInput> = emptyList(),
    val dataInputs: List<UnsignedTxDataInput> = emptyList(),
    val outputs: List<UnsignedTxOutput> = emptyList(),
)

data class UnsignedTxInput(
    val boxId: String,
)

data class UnsignedTxDataInput(
    val boxId: String,
)

data class UnsignedTxOutput(
    val value: String,
    val address: String? = null,
    val ergoTree: String? = null,
    val creationHeight: Int? = null,
    val assets: List<UnsignedTxAsset> = emptyList(),
    val additionalRegisters: Map<String, String>? = null,
)

data class UnsignedTxAsset(
    val tokenId: String,
    val amount: String,
)
