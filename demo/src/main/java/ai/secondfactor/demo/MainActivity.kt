package ai.secondfactor.demo

import ai.secondfactor.SecondFactor
import ai.secondfactor.VerificationReturn
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A two-step sign-in: a password, then the user's phone number verified on the
 * secondfactor.ai hosted page.
 *
 * The app talks only to its own server (demo/server.js). The server holds the
 * secondfactor.ai API key, creates the session, and confirms it at the end;
 * the app only opens the page and hands the return token back.
 */
class MainActivity : ComponentActivity() {
    // The hosted page's answer, set when the return App Link opens this
    // activity and cleared once the screen has acted on it.
    private var verificationReturn by mutableStateOf<VerificationReturn?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On a recreation the intent has been read already, so it is read only
        // on a fresh start.
        if (savedInstanceState == null) verificationReturn = SecondFactor.takeVerificationReturn(intent)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    SignInFlow(
                        verificationReturn = verificationReturn,
                        onReturnHandled = { verificationReturn = null },
                        openVerification = { url -> SecondFactor.openVerification(this, url) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        SecondFactor.takeVerificationReturn(intent)?.let { verificationReturn = it }
    }
}

private enum class Step { SIGN_IN, VERIFY, FINISHING, SIGNED_IN }

@Composable
private fun SignInFlow(
    verificationReturn: VerificationReturn?,
    onReturnHandled: () -> Unit,
    openVerification: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(Step.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    var phone by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Runs whenever the hosted page sends the user back.
    // The return is cleared only once it has been handled: clearing it changes
    // this effect's key, which cancels the effect, request and all.
    LaunchedEffect(verificationReturn) {
        val returnToken = (verificationReturn ?: return@LaunchedEffect).returnToken
        if (returnToken == null) {
            // Canceled, out of attempts, or expired. Nothing to confirm.
            step = Step.VERIFY
            error = "Verification was not completed. Try again."
            onReturnHandled()
            return@LaunchedEffect
        }
        step = Step.FINISHING
        error = null
        try {
            val result = post("/verification/finish", JSONObject().put("returnToken", returnToken), token)
            phone = result.field("phone")
            step = Step.SIGNED_IN
        } catch (e: IOException) {
            step = Step.VERIFY
            error = "Could not finish signing in (${e.message})."
        }
        onReturnHandled()
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            Step.SIGN_IN -> {
                Title("Sign in", "Any email and password works in this demo.")
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Continue", busy, enabled = email.isNotBlank() && password.isNotBlank()) {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val result = post("/signin", JSONObject().put("email", email).put("password", password))
                            token = result.field("token")
                            phone = result.field("phone")
                            step = Step.VERIFY
                        } catch (e: IOException) {
                            error = "Could not sign in (${e.message})."
                        }
                        busy = false
                    }
                }
            }

            Step.VERIFY -> {
                Title("Confirm it's you", "We'll send a code to the phone number ending in ${phone.takeLast(4)}.")
                PrimaryButton("Verify phone number", busy) {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val result = post("/verification/start", JSONObject(), token)
                            openVerification(result.field("url"))
                        } catch (e: IOException) {
                            error = "Could not start verification (${e.message})."
                        } catch (e: IllegalArgumentException) {
                            // The server answered with something that is not a
                            // verification page; the SDK refused to open it.
                            error = "The server sent an invalid verification link."
                        }
                        busy = false
                    }
                }
                TextButton(onClick = { step = Step.SIGN_IN; error = null }) { Text("Use a different account") }
            }

            Step.FINISHING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Finishing sign-in…")
            }

            Step.SIGNED_IN -> {
                Title("You're signed in", "Verified $phone with secondfactor.ai.")
                PrimaryButton("Sign out", busy = false) {
                    step = Step.SIGN_IN
                    token = null
                    password = ""
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

/**
 * POSTs JSON to the demo server and returns its JSON answer. Any failure,
 * including a refusal from the server, is thrown as an IOException whose
 * message is the server's `error` field.
 */
private suspend fun post(path: String, body: JSONObject, token: String? = null): JSONObject =
    withContext(Dispatchers.IO) {
        val connection = URL(BuildConfig.BACKEND_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = connection.responseCode in 200..299
            val text = (if (ok) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                if (ok) throw IOException("the server's answer is not JSON")
                JSONObject()
            }
            if (!ok) throw IOException(json.optString("error", "HTTP ${connection.responseCode}"))
            json
        } finally {
            connection.disconnect()
        }
    }

/** A string field of a server answer, or an IOException when it is missing. */
private fun JSONObject.field(name: String): String =
    optString(name).ifEmpty { throw IOException("the server's answer has no $name") }
