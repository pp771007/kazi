package tw.pp.kazi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.pp.kazi.data.AppDns
import tw.pp.kazi.data.DnsProviders
import tw.pp.kazi.data.DohProvider
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.theme.AppColors
import tw.pp.kazi.util.Logger

/** 測速結果:null = 還沒測 / 用系統 DNS */
private sealed interface ProbeState {
    data object Running : ProbeState
    data class Ok(val ms: Long) : ProbeState
    data object Failed : ProbeState
    data object BadUrl : ProbeState
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DnsSection() {
    val container = LocalAppContainer.current
    val settings by container.configRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()

    // 「自訂」要按套用才生效,所以選中狀態先放本地
    var selected by remember(settings.dnsProvider) { mutableStateOf(settings.dnsProvider) }
    var customUrl by remember(settings.dnsCustomUrl) { mutableStateOf(settings.dnsCustomUrl) }
    var probe by remember { mutableStateOf<ProbeState?>(null) }

    fun applyAndProbe(providerId: String, url: String, provider: DohProvider?) {
        scope.launch {
            container.configRepository.updateDns(providerId, url)
            if (provider == null) {
                probe = null
                return@launch
            }
            probe = ProbeState.Running
            probe = withContext(Dispatchers.IO) {
                runCatching { AppDns.probe(provider) }
                    .fold({ ProbeState.Ok(it) }, {
                        Logger.w("DoH probe failed (${provider.url}): ${it.javaClass.simpleName} ${it.message} / ${it.cause}")
                        ProbeState.Failed
                    })
            }
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusableTag(
            text = "系統預設",
            selected = selected == DnsProviders.SYSTEM_ID,
            onClick = {
                selected = DnsProviders.SYSTEM_ID
                applyAndProbe(DnsProviders.SYSTEM_ID, settings.dnsCustomUrl, null)
            },
        )
        DnsProviders.PRESETS.forEach { p ->
            FocusableTag(
                text = p.label,
                selected = selected == p.id,
                onClick = {
                    selected = p.id
                    applyAndProbe(p.id, settings.dnsCustomUrl, p)
                },
            )
        }
        FocusableTag(
            text = "自訂",
            selected = selected == DnsProviders.CUSTOM_ID,
            onClick = {
                selected = DnsProviders.CUSTOM_ID
                probe = null
            },
        )
    }

    if (selected == DnsProviders.CUSTOM_ID) {
        SyncField(
            label = "DoH 網址",
            placeholder = "https://dns.example.com/dns-query",
            value = customUrl,
            onValueChange = { customUrl = it; probe = null },
        )
        AppButton(
            text = "套用",
            icon = Icons.Filled.Check,
            onClick = {
                val provider = DnsProviders.resolve(DnsProviders.CUSTOM_ID, customUrl)
                if (provider == null) {
                    probe = ProbeState.BadUrl
                } else {
                    applyAndProbe(DnsProviders.CUSTOM_ID, customUrl, provider)
                }
            },
        )
    }

    val (msg, color) = when (val p = probe) {
        null -> return
        ProbeState.Running -> "測試中…" to AppColors.OnBgMuted
        is ProbeState.Ok -> "✓ 正常 · ${p.ms} ms" to AppColors.Success
        ProbeState.Failed -> "✗ 連不上,請換一組" to AppColors.Error
        ProbeState.BadUrl -> "✗ 網址格式不對" to AppColors.Error
    }
    Text(msg, color = color, style = MaterialTheme.typography.bodySmall)
}
