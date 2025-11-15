package org.ergoplatform.ergopay.paymentportal.rest

import org.ergoplatform.ergopay.paymentportal.service.NftMintService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest

@RestController
@CrossOrigin
class NftMintController(
    private val nftMintService: NftMintService
) {

    @PostMapping("/api/nft-mint")
    fun startMint(
        @RequestBody payload: NftMintRequestPayload,
        request: HttpServletRequest,
    ): NftMintInitiationResponse =
        nftMintService.createMintRequest(payload, request)

    @GetMapping("/tx/{txId}")
    fun getMintTransaction(@PathVariable txId: String): ErgoPayResponse =
        nftMintService.buildTransactionResponse(txId)

    @PostMapping("/callback/{txId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun receiveSignedTransaction(
        @PathVariable txId: String,
        @RequestBody payload: MintCallbackPayload,
    ) {
        nftMintService.recordSignedTransaction(txId, payload)
    }
}
