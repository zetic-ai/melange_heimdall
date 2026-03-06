package com.zeticai.melangelm.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeticai.melangelm.demo.DemoUiState
import com.zeticai.melangelm.demo.ExampleCategory
import com.zeticai.melangelm.demo.ExamplePrompt
import com.zeticai.melangelm.demo.EXAMPLE_PROMPTS
import com.zeticai.melangelm.demo.LoadingStatus
import com.zeticai.melangelm.demo.Message

private val Teal = Color(0xFF34A9A3)
private val DangerRed = Color(0xFFD94040)
private val UserBubbleBg = Color(0xFF34A9A3)
private val AssistantBubbleBg = Color(0xFFF2F2F7)

@Composable
fun DemoScreen(
    uiState: DemoUiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onCompressionRatioChange: (Float) -> Unit = {},
    onSendExample: (ExamplePrompt) -> Unit = {},
    onShowSettings: () -> Unit = {},
    onHideSettings: () -> Unit = {},
    onUpdateApiKey: (String) -> Unit = {},
    onUpdateBaseUrl: (String) -> Unit = {},
    onUpdateModel: (String) -> Unit = {}
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showExamples by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = { TopBar(uiState, onClear, onShowSettings, onShowExamples = { showExamples = true }) },
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                SavingsBar(uiState, onCompressionRatioChange, onShowSettings)
                InputBar(
                    text = inputText,
                    enabled = uiState.isReady && !uiState.isLoading,
                    onTextChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText)
                            inputText = ""
                        }
                    },
                    onDismissKeyboard = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                )
            }
        }
    ) { padding ->
        if (uiState.showSettings) {
            SettingsDialog(
                uiState = uiState,
                onDismiss = onHideSettings,
                onUpdateApiKey = onUpdateApiKey,
                onUpdateBaseUrl = onUpdateBaseUrl,
                onUpdateModel = onUpdateModel
            )
        }

        if (showExamples) {
            ExamplesDialog(
                onDismiss = { showExamples = false },
                onSendExample = { example ->
                    onSendExample(example)
                    showExamples = false
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF8F8FB))
        ) {
            if (!uiState.isReady) {
                ModelLoadingPanel(
                    uiState = uiState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            } else if (uiState.messages.isEmpty()) {
                ExamplePromptsPanel(
                    modifier = Modifier.align(Alignment.Center),
                    isLocalMode = uiState.isLocalDemoMode,
                    onSendExample = onSendExample
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.messages) { message ->
                        MessageBubble(message)
                    }
                    if (uiState.isLoading) {
                        item { TypingIndicator() }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(uiState: DemoUiState, onClear: () -> Unit, onShowSettings: () -> Unit = {}, onShowExamples: () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp))
                    Text("Melange LM Proxy", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    uiState.initStatus,
                    fontSize = 11.sp,
                    color = if (uiState.isReady) Teal else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        },
        actions = {
            IconButton(onClick = onShowSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings",
                    tint = if (uiState.hasApiKey) Teal else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (uiState.messages.isNotEmpty() && uiState.isReady) {
                IconButton(onClick = onShowExamples) {
                    Icon(Icons.Default.List, contentDescription = "Examples", tint = Teal)
                }
            }
            if (uiState.messages.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
    )
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    when {
                        message.isBlocked -> DangerRed.copy(alpha = 0.12f)
                        isUser -> UserBubbleBg
                        else -> AssistantBubbleBg
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (message.isBlocked) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = DangerRed, modifier = Modifier.size(16.dp))
                    Column {
                        Text(message.content, color = DangerRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        message.blockedBy?.let { Text("Blocked by: $it", fontSize = 11.sp, color = DangerRed.copy(alpha = 0.7f)) }
                    }
                }
            } else {
                Text(
                    message.content,
                    color = if (isUser) Color.White else Color(0xFF1C1C1E),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
        }

        // Processed content (shows what pipeline did)
        if (message.processedContent != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                message.processedContent,
                fontSize = 12.sp,
                color = Color(0xFF1C1C1E).copy(alpha = 0.8f),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Teal.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        // Pipeline log (assistant only)
        if (!isUser && message.pipelineLog.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                message.pipelineLog.forEach { line ->
                    Text(line, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF666666))
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismissKeyboard: () -> Unit = {}
) {
    Surface(shadowElevation = 8.dp, color = Color.White) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                placeholder = { Text(if (enabled) "Type a message…" else "Loading models…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Teal,
                    unfocusedBorderColor = Color(0xFFDDDDDD)
                )
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FloatingActionButton(
                    onClick = onDismissKeyboard,
                    containerColor = Color(0xFFE8E8E8),
                    modifier = Modifier.size(36.dp),
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close keyboard", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
                FloatingActionButton(
                    onClick = onSend,
                    containerColor = if (enabled && text.isNotBlank()) Teal else Color(0xFFCCCCCC),
                    modifier = Modifier.size(48.dp),
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(AssistantBubbleBg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Teal)
        }
    }
}

@Composable
private fun SavingsBar(uiState: DemoUiState, onRatioChange: (Float) -> Unit, onShowSettings: () -> Unit = {}) {
    Surface(color = Color.White, shadowElevation = 0.dp, tonalElevation = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Mode indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (uiState.hasApiKey) "Full pipeline + upstream LLM" else "Pipeline-only (no API key)",
                    fontSize = 11.sp,
                    color = if (uiState.hasApiKey) Teal else Color(0xFFE67E22)
                )
                Spacer(Modifier.weight(1f))
                if (!uiState.hasApiKey) {
                    TextButton(onClick = onShowSettings, contentPadding = PaddingValues(0.dp)) {
                        Text("Add API key", fontSize = 11.sp, color = Teal)
                    }
                }
            }

            // Compression ratio slider
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Compress", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(58.dp))
                Slider(
                    value = uiState.compressionTargetRatio,
                    onValueChange = onRatioChange,
                    valueRange = 0.2f..0.9f,
                    steps = 6,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal)
                )
                Text(
                    "${(uiState.compressionTargetRatio * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = Teal,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(32.dp)
                )
            }

            // Session savings summary
            if (uiState.totalTokensSaved > 0 || uiState.latestSavings != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Teal.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SavingsStat("Session tokens saved", "${uiState.totalTokensSaved}")
                    SavingsStat("Est. savings", "\$${"%.5f".format(uiState.totalUsdSaved)}")
                    uiState.latestSavings?.let {
                        SavingsStat("Last compression", it.compressionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavingsStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Teal)
        Text(label, fontSize = 9.sp, color = Color.Gray)
    }
}

@Composable
private fun ModelLoadingPanel(
    uiState: DemoUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Shield, null,
            tint = Teal.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp)
        )

        if (uiState.isFirstLaunch) {
            Text("First launch setup", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Downloading 3 on-device AI models.\nThis only happens once — future launches are instant.",
                fontSize = 13.sp, color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Text("Loading on-device models…", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            uiState.loadingSteps.forEach { step ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when (step.status) {
                        LoadingStatus.READY -> Teal.copy(alpha = 0.06f)
                        else -> Color(0xFFF2F2F7)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            when (step.status) {
                                LoadingStatus.PENDING -> Icon(
                                    Icons.Default.Shield, null,
                                    tint = Color.Gray.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                                LoadingStatus.DOWNLOADING, LoadingStatus.LOADING -> CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Teal
                                )
                                LoadingStatus.READY -> Text("✓", color = Teal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                LoadingStatus.FAILED -> Text("✗", color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(step.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(step.description, fontSize = 11.sp, color = Color.Gray)
                            if (step.status == LoadingStatus.DOWNLOADING && step.downloadProgress < 1f) {
                                Spacer(Modifier.height(4.dp))
                                if (step.downloadProgress > 0f) {
                                    LinearProgressIndicator(
                                        progress = { step.downloadProgress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = Teal,
                                        trackColor = Color.Gray.copy(alpha = 0.2f)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = Teal,
                                        trackColor = Color.Gray.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }

                        if (step.status == LoadingStatus.DOWNLOADING && step.downloadProgress > 0f && step.downloadProgress < 1f) {
                            Text(
                                "${(step.downloadProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Teal
                            )
                        } else if (step.status == LoadingStatus.DOWNLOADING && step.downloadProgress == 0f) {
                            Text(
                                "0%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Teal
                            )
                        } else {
                            Text(
                                step.status.label,
                                fontSize = 11.sp,
                                color = if (step.status == LoadingStatus.READY) Teal else Color.Gray
                            )
                        }
                    }
                }
            }
        }

        if (uiState.isFirstLaunch) {
            Text(
                "Models are cached locally — next launch loads in seconds",
                fontSize = 11.sp, color = Color.Gray
            )
        }
    }
}

@Composable
private fun ExamplePromptsPanel(
    modifier: Modifier = Modifier,
    isLocalMode: Boolean,
    onSendExample: (ExamplePrompt) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.Shield, null, tint = Teal.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
        Text("Try the proxy pipeline", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (isLocalMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE67E22).copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Pipeline-only mode", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFFE67E22))
                Text(
                    "See how on-device stages process your messages.\nAdd an API key in settings to get LLM responses.",
                    fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center
                )
            }
        }
        Text("Tap an example to see what happens:", color = Color.Gray, fontSize = 13.sp)

        // Injection examples
        ExampleSection(
            title = "Prompt Injection (blocked on-device)",
            color = DangerRed,
            examples = EXAMPLE_PROMPTS.filter { it.category == ExampleCategory.INJECTION },
            onTap = onSendExample
        )

        // PII examples
        ExampleSection(
            title = "PII Redaction (names, SSN, email hidden)",
            color = Teal,
            examples = EXAMPLE_PROMPTS.filter { it.category == ExampleCategory.PII },
            onTap = onSendExample
        )

        // Long prompt examples
        ExampleSection(
            title = "Token Compression (~45% saved)",
            color = Color(0xFF6B7AE8),
            examples = EXAMPLE_PROMPTS.filter { it.category == ExampleCategory.LONG_PROMPT },
            onTap = onSendExample
        )
    }
}

@Composable
private fun ExampleSection(
    title: String,
    color: Color,
    examples: List<ExamplePrompt>,
    onTap: (ExamplePrompt) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        examples.forEach { example ->
            Surface(
                onClick = { onTap(example) },
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(example.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color)
                    Text(example.description, fontSize = 11.sp, color = Color.Gray)
                    Text(
                        example.prompt.take(80) + if (example.prompt.length > 80) "…" else "",
                        fontSize = 11.sp, color = Color.Gray.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamplesDialog(
    onDismiss: () -> Unit,
    onSendExample: (ExamplePrompt) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Examples") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        ExampleSection(
                            title = "Prompt Injection (blocked on-device)",
                            color = DangerRed,
                            examples = EXAMPLE_PROMPTS.filter { it.category == ExampleCategory.INJECTION },
                            onTap = onSendExample
                        )
                    }
                    item {
                        ExampleSection(
                            title = "PII Redaction (names, SSN, email hidden)",
                            color = Teal,
                            examples = EXAMPLE_PROMPTS.filter { it.category == ExampleCategory.PII },
                            onTap = onSendExample
                        )
                    }
                    item {
                        ExampleSection(
                            title = "Token Compression (~45% saved)",
                            color = Color(0xFF6B7AE8),
                            examples = EXAMPLE_PROMPTS.filter { it.category == ExampleCategory.LONG_PROMPT },
                            onTap = onSendExample
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Teal)
            }
        }
    )
}

@Composable
private fun SettingsDialog(
    uiState: DemoUiState,
    onDismiss: () -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onUpdateModel: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf(uiState.openAIApiKey) }
    var baseUrl by remember { mutableStateOf(uiState.openAIBaseUrl) }
    var model by remember { mutableStateOf(uiState.openAIModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upstream LLM Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Shield, null,
                        tint = if (uiState.hasApiKey) Teal else Color(0xFFE67E22),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        if (uiState.hasApiKey) "Full pipeline mode" else "Pipeline-only mode",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (uiState.hasApiKey) Teal else Color(0xFFE67E22)
                    )
                }

                Text(
                    if (uiState.hasApiKey)
                        "Messages are processed on-device, then sent to the upstream LLM."
                    else
                        "Messages are processed on-device only. Add an API key to enable upstream LLM calls.",
                    fontSize = 11.sp, color = Color.Gray
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key", fontSize = 12.sp) },
                    placeholder = { Text("sk-...", fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Teal)
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL", fontSize = 12.sp) },
                    placeholder = { Text("https://api.openai.com", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Teal)
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model", fontSize = 12.sp) },
                    placeholder = { Text("gpt-4o-mini", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Teal)
                )

                Text(
                    "Works with any OpenAI-compatible API: OpenAI, Groq, Together, Ollama, etc.",
                    fontSize = 10.sp, color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUpdateApiKey(apiKey.trim())
                onUpdateBaseUrl(baseUrl.trim())
                onUpdateModel(model.trim())
                onDismiss()
            }) {
                Text("Save", color = Teal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
