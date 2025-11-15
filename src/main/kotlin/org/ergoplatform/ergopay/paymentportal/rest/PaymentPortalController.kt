package org.ergoplatform.ergopay.paymentportal.rest

import org.ergoplatform.ergopay.paymentportal.service.CreatePaymentRequest
import org.ergoplatform.ergopay.paymentportal.service.PaymentRequestStateResponse
import org.ergoplatform.ergopay.paymentportal.service.PaymentService
import org.springframework.web.bind.annotation.*
import java.util.*
import java.net.URI
import kotlin.text.RegexOption
import javax.servlet.http.HttpServletRequest

@RestController
@CrossOrigin
class PaymentPortalController(
    private val paymentService: PaymentService
) {
    @PostMapping("payment/addrequest")
    fun addPaymentRequest(
        @RequestBody createPaymentRequest: CreatePaymentRequest,
        httpServletRequest: HttpServletRequest
    ): AddRequestResponse {

        val requestId = paymentService.addPaymentRequest(createPaymentRequest)

        // construct ergopay url - we need to find out this server's URL by checking the request
        // we've got
        val baseUrl = httpServletRequest.requestURL.toString()
        val serverRequestUrl = baseUrl.substring(0, baseUrl.indexOf("payment/addrequest"))
        val serverUri = URI(serverRequestUrl)
        val scheme = serverUri.scheme?.lowercase()
        val host = (serverUri.host ?: httpServletRequest.serverName) ?: ""
        val hostWithoutWww = host.replaceFirst(Regex("^www\\.", RegexOption.IGNORE_CASE), "")
        val detectedPort = if (serverUri.port == -1) httpServletRequest.serverPort else serverUri.port
        val portPart = when {
            detectedPort <= 0 -> ""
            scheme == "http" && detectedPort == 80 -> ""
            scheme == "https" && detectedPort == 443 -> ""
            else -> ":$detectedPort"
        }
        val rawPath = serverUri.path?.takeIf { it.isNotBlank() } ?: httpServletRequest.contextPath ?: "/"
        val basePath = if (rawPath.endsWith("/")) rawPath else "$rawPath/"
        val ergoPayUrl = ("ergopay://" + hostWithoutWww + portPart + basePath +
            "payment/getrequest/$requestId" +
                (if (createPaymentRequest.senderAddress == null) "?sender=#P2PK_ADDRESS#" else "")).replace("ergopay://www.", "ergopay://")

        return AddRequestResponse(requestId, ergoPayUrl)
    }


    @GetMapping("payment/auth")
    fun requestWalletAddress(
        @RequestParam(required = false) message: String?,
        @RequestParam(required = false) replyTo: String?,
    ): ErgoPayResponse {
        val response = ErgoPayResponse()
        response.messageSeverity = ErgoPayResponse.Severity.INFORMATION
        response.message = message ?: "Please confirm to share your sending address."
        response.address = "#P2PK_ADDRESS#"
        if (!replyTo.isNullOrBlank()) {
            response.replyTo = replyTo
        }
        return response
    }

    @GetMapping("payment/getrequest/{requestId}")
    fun getPaymentRequest(
        @PathVariable requestId: String,
        @RequestParam(required = false) sender: String?,
    ): ErgoPayResponse {
        val response = ErgoPayResponse()

        try {
            val paymentRequest = paymentService.getReducedTransactionForRequest(requestId, sender)
            response.address = paymentRequest.senderAddress
            response.reducedTx = Base64.getUrlEncoder().encodeToString(paymentRequest.reducedTx)

            response.messageSeverity = ErgoPayResponse.Severity.INFORMATION

            response.message =
                "Your transaction has been prepared. Please check the details and confirm " +
                        "the transaction."

        } catch (t: Throwable) {
            response.messageSeverity = ErgoPayResponse.Severity.ERROR
            response.message =
                "Could not prepare your buy transaction:\n" + t.message?.take(1000)
        }
        return response
    }

    @GetMapping("payment/state/{requestId}")
    fun getPaymentState(
        @PathVariable requestId: String,
    ): PaymentRequestStateResponse {
        return paymentService.getPaymentState(requestId)
    }
}

data class AddRequestResponse(
    val requestId: String,
    val ergoPayUrl: String,
)
