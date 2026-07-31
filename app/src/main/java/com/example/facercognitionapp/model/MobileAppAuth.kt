package com.example.facercognitionapp.model

import com.google.gson.annotations.SerializedName

data class MobileAppAuthRequest(
    @SerializedName("EmailId")
    val emailId: String,

    @SerializedName("MobileAppPassword")
    val mobileAppPassword: String
)

data class MobileAppAuthResponse(
    @SerializedName("success")
    val success: Boolean = false,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("data")
    val data: MobileAppUserData? = null
)

data class MobileAppUserData(
    @SerializedName("userId")
    val userId: Int? = null,

    @SerializedName("userCompanyRightsId")
    val userCompanyRightsId: Int? = null,

    @SerializedName("companyId")
    val companyId: Int? = null,

    @SerializedName("branchId")
    val branchId: Int? = null,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("address")
    val address: String? = null,

    @SerializedName("contactNo")
    val contactNo: String? = null,

    @SerializedName("emailId")
    val emailId: String? = null,

    @SerializedName("userName")
    val userName: String? = null,

    @SerializedName("password")
    val password: String? = null,

    @SerializedName("mobileAppPassword")
    val mobileAppPassword: String? = null,

    @SerializedName("userType")
    val userType: String? = null,

    @SerializedName("isActive")
    val isActive: String? = null
)
