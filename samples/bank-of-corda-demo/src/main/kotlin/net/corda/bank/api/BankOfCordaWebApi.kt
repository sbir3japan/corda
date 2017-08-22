package net.corda.bank.api

import net.corda.core.contracts.Amount
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.flows.CashIssueAndPaymentFlow
import org.bouncycastle.asn1.x500.X500Name
import java.time.LocalDateTime
import java.util.*
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// API is accessible from /api/bank. All paths specified below are relative to it.
@Path("bank")
class BankOfCordaWebApi(val rpc: CordaRPCOps) {
    data class IssueRequestParams(val amount: Long, val currency: String,
                                  val issueToPartyName: X500Name, val issuerBankPartyRef: String,
                                  val issuerBankName: X500Name,
                                  val notaryName: X500Name,
                                  val anonymous: Boolean)

    private companion object {
        val logger = loggerFor<BankOfCordaWebApi>()
    }

    @GET
    @Path("date")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCurrentDate(): Any {
        return mapOf("date" to LocalDateTime.now().toLocalDate())
    }

    /**
     *  Request asset issuance
     */
    @POST
    @Path("issue-asset-request")
    @Consumes(MediaType.APPLICATION_JSON)
    fun issueAssetRequest(params: IssueRequestParams): Response {
        // Resolve parties via RPC
        val issueToParty = rpc.partyFromX500Name(params.issueToPartyName)
                ?: return Response.status(Response.Status.FORBIDDEN).entity("Unable to locate ${params.issueToPartyName} in identity service").build()
        rpc.partyFromX500Name(params.issuerBankName) ?: return Response.status(Response.Status.FORBIDDEN).entity("Unable to locate ${params.issuerBankName} in identity service").build()
        val notaryParty = rpc.partyFromX500Name(params.notaryName)
                ?: return Response.status(Response.Status.FORBIDDEN).entity("Unable to locate ${params.notaryName} in identity service").build()
        val notaryNode = rpc.nodeIdentityFromParty(notaryParty)
                ?: return Response.status(Response.Status.FORBIDDEN).entity("Unable to locate $notaryParty in network map service").build()

        val amount = Amount(params.amount, Currency.getInstance(params.currency))
        val issuerBankPartyRef = OpaqueBytes.of(params.issuerBankPartyRef.toByte())

        // invoke client side of Issuer Flow: IssuanceRequester
        // The line below blocks and waits for the future to resolve.
        return try {
            rpc.startFlow(::CashIssueAndPaymentFlow, amount, issuerBankPartyRef, issueToParty, params.anonymous, notaryNode.notaryIdentity).returnValue.getOrThrow()
            logger.info("Issue and payment request completed successfully: $params")
            Response.status(Response.Status.CREATED).build()
        } catch (e: Exception) {
            logger.error("Issue and payment request failed", e)
            Response.status(Response.Status.FORBIDDEN).build()
        }
    }
}