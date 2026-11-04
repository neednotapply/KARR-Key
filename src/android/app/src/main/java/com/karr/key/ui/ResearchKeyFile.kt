package com.karr.key.ui

import com.karr.key.protocol.ResearchKeyEncoding
import org.json.JSONException
import org.json.JSONObject

/** Keys read from a delivery file. Arrays are owned by the caller, who should call [wipe]. */
internal class ResearchKeyFile(val customer: ByteArray?, val dealer: ByteArray?) {
    fun wipe() {
        customer?.fill(0)
        dealer?.fill(0)
    }

    companion object {
        /**
         * Parses a key delivery file of the form
         * `{"Customer_Key": "<16 hex bytes>", "Dealer_Key": "<16 hex bytes>"}`.
         *
         * Either key may be omitted; at least one must be present. Key names are matched
         * case-insensitively and ignore `_`, `-` and spaces. Lives in the Android app module
         * (not the platform-free protocol module) because it uses Android's bundled `org.json`.
         */
        fun parse(json: String): Result<ResearchKeyFile> = runCatching {
            val root = try {
                JSONObject(json.trimStart('﻿'))
            } catch (_: JSONException) {
                throw IllegalArgumentException("The file is not valid JSON.")
            }
            var customer: ByteArray? = null
            var dealer: ByteArray? = null
            try {
                root.keys().forEach { name ->
                    val slot = name.filter { it.isLetter() }.lowercase()
                    if (slot != "customerkey" && slot != "dealerkey") return@forEach
                    val text = root.opt(name) as? String
                        ?: throw IllegalArgumentException("$name must be a hex string.")
                    val bytes = ResearchKeyEncoding.parseLegacyHex(text).getOrElse {
                        throw IllegalArgumentException("$name: ${it.message}")
                    }
                    if (slot == "customerkey") customer = bytes else dealer = bytes
                }
                require(customer != null || dealer != null) {
                    "No Customer_Key or Dealer_Key found in the file."
                }
            } catch (failure: Throwable) {
                customer?.fill(0)
                dealer?.fill(0)
                throw failure
            }
            ResearchKeyFile(customer = customer, dealer = dealer)
        }
    }
}
