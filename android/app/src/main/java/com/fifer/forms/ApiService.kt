package com.fifer.forms

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @GET("domains")
    suspend fun getDomains(): List<String>

    @GET("domains/{domain}/config")
    suspend fun getDomainConfig(@Path("domain") domain: String): DomainConfig

    @POST("domains/{domain}/parse")
    suspend fun parseDomain(
        @Path("domain") domain: String,
        @Body body: Map<String, String>
    ): ParseResponse

    @POST("domains/{domain}/records")
    suspend fun createRecord(
        @Path("domain") domain: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): RecordResponse

    @GET("domains/{domain}/records")
    suspend fun getRecords(@Path("domain") domain: String): List<Map<String, @JvmSuppressWildcards Any>>
}

data class ParseResponse(
    val prefilled: Map<String, @JvmSuppressWildcards Any>,
    val warnings: List<String>,
    @SerializedName("field_warnings")
    val fieldWarnings: Map<String, String> = emptyMap()
)

data class RecordResponse(
    val id: Int,
    val data: Map<String, Any>
)

object RetrofitClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl("http://10.0.2.2:8000/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ApiService by lazy { retrofit.create(ApiService::class.java) }
}
