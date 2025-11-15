package org.ergoplatform.ergopay.paymentportal.rest

import com.fasterxml.jackson.annotation.JsonInclude

@JvmInline
value class ErgoAddress(val value: String)

data class NftMintRequestPayload(
    val address: String,
    val imageUrl: String,
    val name: String,
    val description: String,
    val collectionName: String? = null,
    val attributes: List<NftMintAttribute> = emptyList(),
    val imageHash: String? = null,
)

data class NftMintAttribute(
    val trait_type: String,
    val value: String,
)

data class NftMintInitiationResponse(
    val success: Boolean,
    val txId: String,
    val tokenId: String?,
    val ergoPayUrl: String,
)

data class MintCallbackPayload(
    val signedTxId: String,
)

