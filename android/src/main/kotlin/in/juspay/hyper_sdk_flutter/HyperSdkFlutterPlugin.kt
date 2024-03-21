/*
 * Copyright (c) Juspay Technologies.
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package `in`.juspay.hyper_sdk_flutter

import `in`.juspay.hypersdk.data.JuspayResponseHandler
import `in`.juspay.hypersdk.ui.HyperPaymentsCallbackAdapter
import `in`.juspay.services.HyperServices
import android.content.Intent
import android.util.Log
import androidx.annotation.NonNull
import androidx.fragment.app.FragmentActivity
import `in`.juspay.hypercheckoutlite.HyperCheckoutLite
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONObject

class HyperSdkFlutterPlugin: FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
    private lateinit var channel: MethodChannel
    private var binding: ActivityPluginBinding? = null
    private var hyperServicesMap: MutableMap<String, HyperServices> = HashMap()
    private var isHyperCheckOutLiteInteg: Boolean = false

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "hyperSDK")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        this.binding!!.removeActivityResultListener(this)
        this.binding = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        try {
            hyperServicesMap.forEach {
                (clientId, hyperServices) ->
                    hyperServices.onActivityResult(requestCode, resultCode, data!!)
            }
            return true
        } catch (e: Exception) {
            return false;
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        val clientId = call.argument<String>("clientId")
        when (call.method) {
            "preFetch" -> preFetch(call.argument<Map<String, Any>>("params"), clientId, result)
            "initiate" -> initiate(call.argument<Map<String, Any>>("params"), clientId, result)
            "process" -> process(call.argument<Map<String, Any>>("params"), clientId, result)
            "terminate" -> terminate(clientId, result)
            "isInitialised" -> isInitialised(clientId, result)
            "onBackPress" -> onBackPress(clientId, result)
            "openPaymentPage" -> openPaymentPage(call.argument<Map<String, Any>>("params"), clientId, result)
            else -> result.notImplemented()
        }
    }

    private fun onBackPress(clientId: String?, result: Result) {
        try {
            if (isHyperCheckOutLiteInteg) {
                val backPress = HyperCheckoutLite.onBackPressed();
                result.success(backPress)
            }else{
                val backPress = hyperServicesMap.get(clientId)!!.onBackPressed()
                result.success(backPress)
            }
        } catch(e: Exception) {
            result.error("HYPERSDKFLUTTER: backpress error", e.localizedMessage, e)
        }
    }

    private fun isInitialised(clientId: String?, result: Result) {
        try {
            val isInitiated: Boolean
            if (hyperServicesMap.get(clientId) == null) {
                isInitiated = false
            } else {
                isInitiated = hyperServicesMap.get(clientId)!!.isInitialised
            }
            result.success(isInitiated)
        } catch(e: Exception) {
            result.success(false)
        }
    }

    private fun preFetch(params: Map<String, Any>?, clientId: String?, result: Result) {
        try {
            HyperServices.preFetch(binding!!.activity, JSONObject(params), clientId)
            result.success(true)
        } catch (e: Exception) {
            result.error("HYPERSDKFLUTTER: prefetch error", e.message, e)
        }
    }

    private fun initiate(params: Map<String, Any>?, clientId: String?, result: Result) = try {
        println("client: $clientId")
        val fragmentActivity = binding!!.activity as FragmentActivity
        val hyperServices = HyperServices(fragmentActivity, clientId)
        hyperServicesMap.put(clientId!!, hyperServices)

        val invokeMethodResult = object : Result {
            override fun success(result: Any?) {
                Log.d(this.javaClass.canonicalName, "success: ${result.toString()}")
                println("result = ${result.toString()}")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.e(this.javaClass.canonicalName, "$errorCode\n$errorMessage")
            }

            override fun notImplemented() {
                Log.e(this.javaClass.canonicalName, "notImplemented")
            }
        }
        val callback = object : HyperPaymentsCallbackAdapter() {
            override fun onEvent(data: JSONObject, p1: JuspayResponseHandler?) {
                try {
                    channel.invokeMethod(data.getString("event"), data.toString(), invokeMethodResult)
                } catch (e: Exception) {
                    Log.e(this.javaClass.canonicalName, "Failed to invoke method from native to dart", e)
                }
            }
        }
        if(!(binding!!.activity is FragmentActivity)){
            Log.e("JUSPAY", "Kotlin MainActivity should extend FlutterFragmentActivity instead of FlutterActivity! JUSPAY Plugin only supports FragmentActivity. Please refer to this doc for more information: https://juspaydev.vercel.app/sections/base-sdk-integration/initiating-sdk?platform=Flutter&product=Payment+Page")
            throw Exception("Kotlin MainActivity should extend FlutterFragmentActivity instead of FlutterActivity!");
        }
        hyperServices!!.initiate(binding!!.activity as FragmentActivity, JSONObject(params), callback)
        result.success(true)
    } catch (e: Exception) {
        result.error("INIT_ERROR", e.localizedMessage, e)
    }

    private fun process(params: Map<String, Any>?, clientId: String?, result: Result) {
        if (hyperServicesMap.get(clientId) != null) {
            hyperServicesMap.get(clientId)!!.process(JSONObject(params))
            result.success(true)
        } else {
            Log.e(this.javaClass.canonicalName, "initiate() must be called before calling process()")
            result.success(false)
        }
    }

    private fun openPaymentPage(params: Map<String, Any>?, clientId: String?, result : Result) {
        isHyperCheckOutLiteInteg = true
        val invokeMethodResult = object : Result {
            override fun success(result: Any?) {
                Log.d(this.javaClass.canonicalName, "success: ${result.toString()}")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.e(this.javaClass.canonicalName, "$errorCode\n$errorMessage")
            }

            override fun notImplemented() {
                Log.e(this.javaClass.canonicalName, "notImplemented")
            }
        }
        val callback = object : HyperPaymentsCallbackAdapter() {
            override fun onEvent(data: JSONObject, p1: JuspayResponseHandler?) {
                try {
                    channel.invokeMethod(data.getString("event"), data.toString(), invokeMethodResult)
                } catch (e: Exception) {
                    Log.e(this.javaClass.canonicalName, "Failed to invoke method from native to dart", e)
                }
            }
        }
        if(!(binding!!.activity is FragmentActivity)){
            throw Exception("Kotlin MainActivity should extend FlutterFragmentActivity instead of FlutterActivity!");
        }
        HyperCheckoutLite.openPaymentPage(binding!!.activity as FragmentActivity,
            params?.let { JSONObject(it) }, callback)
    }

    private fun terminate(clientId: String?, result: Result) {
        if (hyperServicesMap.get(clientId) != null) {
            hyperServicesMap.get(clientId)!!.terminate()
            result.success(true)
        } else {
            Log.w(this.javaClass.canonicalName, "Terminate called without initiate, skipping")
            result.success(false)
        }
    }
}
