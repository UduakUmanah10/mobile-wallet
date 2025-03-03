package org.mifos.mobilewallet.mifospay.registration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mifos.mobilewallet.model.State
import com.mifos.mobilewallet.model.domain.user.NewUser
import com.mifos.mobilewallet.model.domain.user.UpdateUserEntityClients
import com.mifos.mobilewallet.model.domain.user.User
import com.mifos.mobilewallet.model.entity.UserWithRole
import com.mifos.mobilewallet.model.signup.SignupData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.mifos.mobilewallet.core.base.UseCase
import org.mifos.mobilewallet.core.base.UseCaseHandler
import org.mifos.mobilewallet.core.domain.usecase.client.CreateClient
import org.mifos.mobilewallet.core.domain.usecase.client.FetchClientData
import org.mifos.mobilewallet.core.domain.usecase.client.SearchClient
import org.mifos.mobilewallet.core.domain.usecase.user.AuthenticateUser
import org.mifos.mobilewallet.core.domain.usecase.user.CreateUser
import org.mifos.mobilewallet.core.domain.usecase.user.DeleteUser
import org.mifos.mobilewallet.core.domain.usecase.user.FetchUserDetails
import org.mifos.mobilewallet.core.domain.usecase.user.UpdateUser
import org.mifos.mobilewallet.core.repository.local.LocalAssetRepository
import org.mifos.mobilewallet.datastore.PreferencesHelper
import org.mifos.mobilewallet.mifospay.utils.Constants
import org.mifos.mobilewallet.mifospay.utils.DebugUtil
import javax.inject.Inject


@HiltViewModel
class SignupViewModel @Inject constructor(
    localAssetRepository: LocalAssetRepository,
    private val useCaseHandler: UseCaseHandler,
    private val preferencesHelper: PreferencesHelper,
    private val searchClientUseCase: SearchClient,
    private val createClientUseCase: CreateClient,
    private val createUserUseCase: CreateUser,
    private val updateUserUseCase: UpdateUser,
    private val authenticateUserUseCase: AuthenticateUser,
    private val fetchClientDataUseCase: FetchClientData,
    private val deleteUserUseCase: DeleteUser,
    private val fetchUserDetailsUseCase: FetchUserDetails
) : ViewModel() {

    var showProgress by mutableStateOf(false)
    var isLoginSuccess by mutableStateOf(false)

    var signupData by mutableStateOf(SignupData())
    var state by mutableStateOf<State?>(null)

    fun initSignupData(
        savingProductId: Int,
        mobileNumber: String,
        countryName: String?,
        email: String?,
        firstName: String?,
        lastName: String?,
        businessName: String?
    ) {
        signupData = signupData.copy(
            mifosSavingsProductId = savingProductId,
            mobileNumber = mobileNumber,
            countryName = countryName,
            email = email,
            firstName = firstName!!,
            lastName = lastName!!,
            businessName = businessName
        )
    }

    val states: StateFlow<List<State>> = combine(
        localAssetRepository.getCountries(),
        localAssetRepository.getStateList(),
        ::Pair
    )
        .map {
            val countries = it.first
            signupData = signupData.copy(
                countryId = countries.find { it.name == signupData.countryName }?.id ?: ""
            )
            it.second.filter { it.countryId == signupData.countryId }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun registerUser(data: SignupData, showToastMessage: (String) -> Unit) {
        signupData = data

        // 0. Unique Mobile Number (checked in MOBILE VERIFICATION ACTIVITY)
        // 1. Check for unique external id and username
        // 2. Create user
        // 3. Create Client
        // 4. Update User and connect client with user
        useCaseHandler.execute(searchClientUseCase,
            SearchClient.RequestValues("${signupData.userName}@mifos"),
            object : UseCase.UseCaseCallback<SearchClient.ResponseValue> {
                override fun onSuccess(response: SearchClient.ResponseValue) {
                    showToastMessage("Username already exists.")
                }

                override fun onError(message: String) {
                    createUser(showToastMessage)
                }
            })
    }

    private fun createUser(showToastMessage: (String) -> Unit) {
        val newUser = NewUser(
            signupData.userName, signupData.firstName, signupData.lastName,
            signupData.email, signupData.password
        )
        useCaseHandler.execute(createUserUseCase, CreateUser.RequestValues(newUser),
            object : UseCase.UseCaseCallback<CreateUser.ResponseValue> {
                override fun onSuccess(response: CreateUser.ResponseValue) {
                    createClient(response.userId, showToastMessage)
                }

                override fun onError(message: String) {
                    DebugUtil.log(message)
                    showToastMessage(message)
                }
            })
    }

    private fun createClient(userId: Int, showToastMessage: (String) -> Unit) {
        val newClient = com.mifos.mobilewallet.model.domain.client.NewClient(
            signupData.businessName, signupData.userName, signupData.addressLine1,
            signupData.addressLine2, signupData.city, signupData.pinCode, signupData.stateId,
            signupData.countryId, signupData.mobileNumber, signupData.mifosSavingsProductId
        )
        useCaseHandler.execute(createClientUseCase,
            CreateClient.RequestValues(newClient),
            object : UseCase.UseCaseCallback<CreateClient.ResponseValue> {
                override fun onSuccess(response: CreateClient.ResponseValue) {
                    response.clientId.let { DebugUtil.log(it) }
                    val clients = ArrayList<Int>()
                    response.clientId.let { clients.add(it) }
                    updateClient(clients, userId, showToastMessage)
                }

                override fun onError(message: String) {
                    // delete user
                    DebugUtil.log(message)
                    showToastMessage(message)
                    deleteUser(userId)
                }
            })
    }

    private fun updateClient(
        clients: ArrayList<Int>,
        userId: Int,
        showToastMessage: (String) -> Unit
    ) {
        useCaseHandler.execute(updateUserUseCase,
            UpdateUser.RequestValues(UpdateUserEntityClients(clients), userId),
            object : UseCase.UseCaseCallback<UpdateUser.ResponseValue?> {
                override fun onSuccess(response: UpdateUser.ResponseValue?) {
                    loginUser(signupData.userName, signupData.password, showToastMessage)
                }

                override fun onError(message: String) {
                    // connect client later
                    DebugUtil.log(message)
                    showToastMessage("update client error")
                }
            })
    }

    private fun loginUser(
        username: String?,
        password: String?,
        showToastMessage: (String) -> Unit
    ) {
        authenticateUserUseCase.walletRequestValues = AuthenticateUser.RequestValues(username, password)
        val requestValue = authenticateUserUseCase.walletRequestValues
        useCaseHandler.execute(authenticateUserUseCase, requestValue,
            object : UseCase.UseCaseCallback<AuthenticateUser.ResponseValue> {
                override fun onSuccess(response: AuthenticateUser.ResponseValue) {
                    response?.user?.let { createAuthenticatedService(it) }
                    fetchClientData(showToastMessage)
                    response?.user?.let { fetchUserDetails(it) }
                }

                override fun onError(message: String) {
                    showToastMessage("Login Failed")
                }
            })
    }

    private fun fetchUserDetails(user: User) {
        useCaseHandler.execute(fetchUserDetailsUseCase,
            FetchUserDetails.RequestValues(user.userId),
            object : UseCase.UseCaseCallback<FetchUserDetails.ResponseValue> {
                override fun onSuccess(response: FetchUserDetails.ResponseValue) {
                    response?.userWithRole?.let { saveUserDetails(user, it) }
                }

                override fun onError(message: String) {
                    DebugUtil.log(message)
                }
            })
    }

    private fun fetchClientData(showToastMessage: (String) -> Unit) {
        useCaseHandler.execute(fetchClientDataUseCase, null,
            object : UseCase.UseCaseCallback<FetchClientData.ResponseValue> {
                override fun onSuccess(response: FetchClientData.ResponseValue) {
                    saveClientDetails(response.userDetails)
                    if (response.userDetails.name != "") {
                        isLoginSuccess = true
                    }
                }

                override fun onError(message: String) {
                    showToastMessage("Fetch Client Error")
                }
            })
    }

    private fun createAuthenticatedService(user: User) {
        val authToken = Constants.BASIC + user.authenticationKey
        preferencesHelper.saveToken(authToken)
    }

    private fun saveUserDetails(
        user: User,
        userWithRole: UserWithRole
    ) {
        val userName = user.userName
        val userID = user.userId
        preferencesHelper.saveUsername(userName)
        preferencesHelper.userId = userID
        preferencesHelper.saveEmail(userWithRole.email)
    }

    private fun saveClientDetails(client: com.mifos.mobilewallet.model.domain.client.Client) {
        preferencesHelper.saveFullName(client.name)
        preferencesHelper.clientId = client.clientId
        preferencesHelper.saveMobile(client.mobileNo)
    }

    private fun deleteUser(userId: Int) {
        useCaseHandler.execute(deleteUserUseCase, DeleteUser.RequestValues(userId),
            object : UseCase.UseCaseCallback<DeleteUser.ResponseValue> {
                override fun onSuccess(response: DeleteUser.ResponseValue) {}
                override fun onError(message: String) {}
            })
    }
}

sealed interface SignupUiState {
    data object None : SignupUiState
    data object Success : SignupUiState
    data class Error(val exception: String) : SignupUiState
}
