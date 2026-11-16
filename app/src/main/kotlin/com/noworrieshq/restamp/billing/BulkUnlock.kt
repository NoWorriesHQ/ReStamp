package com.noworrieshq.restamp.billing

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.noworrieshq.restamp.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one-time "bulk copy" purchase through Google Play. Everything else in the app is free.
 * The unlock is cached so bulk copy works offline, and rechecked with Play on every start so
 * a refund takes it away again. Test (debug) builds are always unlocked, because a sideloaded
 * APK can't reach the Play store's products.
 */
class BulkUnlock(context: Context, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    private val prefs = context.getSharedPreferences("unlock", Context.MODE_PRIVATE)

    var unlocked by mutableStateOf(BuildConfig.DEBUG || prefs.getBoolean(KEY, false)); private set
    /** Local price text from Play, such as "$4.99", once known. */
    var price by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var pending by mutableStateOf(false); private set

    private var product: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (client.isReady) { refresh(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) refresh()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun refresh() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) handle(purchases, fromRestore = true)
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(ProductType.INAPP).build()),
        ).build()
        client.queryProductDetailsAsync(params) { _, result ->
            val details = result.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            onMain {
                product = details
                price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }
    }

    /** Opens Google Play's purchase sheet. */
    fun buy(activity: Activity) {
        val details = product
        if (details == null) {
            message = "Google Play isn't reachable right now. Check you're online and signed in to the Play Store, then try again."
            start()
            return
        }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
        ).build()
        client.launchBillingFlow(activity, flow)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> handle(purchases.orEmpty(), fromRestore = false)
            BillingResponseCode.ITEM_ALREADY_OWNED -> start()
            BillingResponseCode.USER_CANCELED -> {}
            else -> onMain { message = "The purchase didn't go through (${result.debugMessage.ifBlank { "code ${result.responseCode}" }})." }
        }
    }

    private fun handle(purchases: List<Purchase>, fromRestore: Boolean) {
        val purchase = purchases.firstOrNull { PRODUCT_ID in it.products }
        val owned = purchase?.purchaseState == Purchase.PurchaseState.PURCHASED
        if (owned && !purchase.isAcknowledged) {
            // Play refunds purchases that aren't acknowledged within three days.
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) {}
        }
        onMain {
            pending = purchase?.purchaseState == Purchase.PurchaseState.PENDING
            if (owned) {
                if (!unlocked && !fromRestore) message = "Bulk copy is unlocked. Thank you!"
                rememberOwned(true)
            } else if (fromRestore) {
                // Play is the source of truth: no purchase (or a refund) means locked.
                rememberOwned(false)
            } else if (pending) {
                message = "Your payment is pending. Bulk copy unlocks as soon as Google Play confirms it."
            }
        }
    }

    private fun rememberOwned(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        unlocked = BuildConfig.DEBUG || value
    }

    private fun onMain(block: () -> Unit) { scope.launch(Dispatchers.Main) { block() } }

    companion object {
        /** Must match the one-time product created in Play Console. */
        const val PRODUCT_ID = "bulk_copy"
        private const val KEY = "bulk_copy_owned"
    }
}
