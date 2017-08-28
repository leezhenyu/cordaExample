package com.example.flow


import co.paralleluniverse.fibers.Suspendable
import com.example.contract.OrderContract
import com.example.flow.CreateOrderFlow.Acceptor
import com.example.flow.CreateOrderFlow.Initiator
import com.example.state.OrderState
import net.corda.core.contracts.Command
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object CreateOrderFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val orderState: OrderState,
                    val otherParty: Party): FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new bl.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val txCommand = Command(OrderContract.Commands.Issue(), listOf(orderState.seller.owningKey, orderState.buyer.owningKey))
            val txBuilder = TransactionBuilder(TransactionType.General, notary).withItems(orderState, txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.toWireTransaction().toLedgerTransaction(serviceHub).verify()

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker())).single()
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherParty: Party) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherParty) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an order transaction." using (output is OrderState)
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}