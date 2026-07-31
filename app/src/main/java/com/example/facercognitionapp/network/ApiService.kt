package com.example.facercognitionapp.network

import com.example.facercognitionapp.model.LoginRequest
import com.example.facercognitionapp.model.MobileAppAuthRequest
import com.example.facercognitionapp.model.MobileAppAuthResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST("MobileAppAuthentication")
    suspend fun mobileAppAuthentication(
        @Body request: MobileAppAuthRequest
    ): Response<MobileAppAuthResponse>

    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST("EmployeeAuthentication")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ResponseBody>

    /**
     * POST /Recognize
     * form-data: file, inOutFlag, Latitude, Longitude, CompanyId
     * Success response: { "message": "IN Punch Done Successfully" }
     */
    @Multipart
    @Headers("Accept: application/json")
    @POST("Recognize")
    suspend fun recognize(
        @Part file: MultipartBody.Part,
        @Part("inOutFlag") inOutFlag: RequestBody,
        @Part("Latitude") latitude: RequestBody,
        @Part("Longitude") longitude: RequestBody,
        @Part("CompanyId") companyId: RequestBody
    ): Response<ResponseBody>
}
