package org.ergoplatform.ergopay.paymentportal.rest

import org.ergoplatform.ergopay.paymentportal.service.ReducedTxService
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest

@RestController
@CrossOrigin
class ReducedTxController(
    private val reducedTxService: ReducedTxService
) {

    @PostMapping("/api/v1/reducedTx")
    fun submitReducedTransaction(
        @RequestBody payload: ReducedTxSubmissionRequest,
        request: HttpServletRequest,
    ): ReducedTxRegistrationResponse {
        val baseHttpUrl = request.requestURL.toString().removeSuffix(request.requestURI)
        return reducedTxService.register(payload, baseHttpUrl)
    }

    @GetMapping("/payment/sign/{requestId}")
    fun getReducedTransaction(
        @PathVariable requestId: String
    ): ErgoPayResponse = reducedTxService.buildResponse(requestId)
}
