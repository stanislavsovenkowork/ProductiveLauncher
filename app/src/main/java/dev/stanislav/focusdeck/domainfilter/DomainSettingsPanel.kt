package dev.stanislav.focusdeck.domainfilter

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One-time optional setup. No VPN permission dialog is launched by composition or settings restore. */
@Composable
fun DomainSettingsPanel(
    controller:DomainFilterController,
    installedApps:List<DomainAppChoice>,
    modifier:Modifier=Modifier,
    onRequestFullExit:(()->Unit)?=null,
) {
    val state by controller.state.collectAsState()
    val context=LocalContext.current
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { controller.notificationsResult(it) }
    var accepted by remember { mutableStateOf(false) }
    var protectedInput by remember { mutableStateOf("") }
    var blockedInput by remember { mutableStateOf("") }
    var confirmDnsStop by remember { mutableStateOf(false) }
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.consentResult(it.resultCode==Activity.RESULT_OK)
    }
    fun launch(action:DomainConsentAction) {
        if(action is DomainConsentAction.Launch)runCatching { consent.launch(action.intent) }.onFailure { controller.consentUnavailable() }
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Сайты · частичная DNS-проба",style=MaterialTheme.typography.titleMedium)
        Text(if(state.runtime.active)"Служба активна · только DNS выбранных приложений" else "Служба не активна · сайты не объявляются защищёнными",
            style=MaterialTheme.typography.bodyMedium)
        Text("Выбор для нового расписания защиты",style=MaterialTheme.typography.labelLarge)
        Text("Уже сохранённое расписание содержит неизменный снимок. Чтобы заменить его, отключите и создайте расписание заново. Действующее окно не меняется.",style=MaterialTheme.typography.bodySmall)
        Text("Выберите браузеры. Это отдельный список: браузер не закрывается целиком.",style=MaterialTheme.typography.bodySmall)
        val choices=installedApps.filter { controller.canSelectPackage(it.packageName) }.distinctBy { it.packageName }
        if(choices.isEmpty())Text("Нет доступных приложений для выбора · DNS-проба не включается",style=MaterialTheme.typography.bodySmall)
        choices.forEach { app ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(app.label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Checkbox(checked=app.packageName in state.setup.packages,onCheckedChange={controller.selectPackage(app.packageName,it)})
            }
        }
        Text("Точные имена следующего снимка: "+state.setup.blocked.sorted().joinToString(" · "),style=MaterialTheme.typography.bodySmall)
        DisclosureDomains(state.setup,controller)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=blockedInput,onValueChange={blockedInput=it},label={Text("Точный блокируемый домен")},singleLine=true,modifier=Modifier.weight(1f))
            TextButton(onClick={controller.selectDomain(blockedInput,true);blockedInput=""}){Text("Добавить")}
        }
        Text("Блокируются только перечисленные точные имена, а не все поддомены. Для каждого нужного имени добавьте отдельную запись.",style=MaterialTheme.typography.bodySmall)
        Text("Всегда разрешены рабочий GitLab, OpenAI и ChatGPT, включая поддомены. Дополнительные рабочие имена:",style=MaterialTheme.typography.bodySmall)
        Text((DomainPolicy.INFRASTRUCTURE+state.setup.additionalProtected).sorted().joinToString(" · "),style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=protectedInput,onValueChange={protectedInput=it},label={Text("Рабочий домен")},singleLine=true,modifier=Modifier.weight(1f))
            TextButton(onClick={controller.addProtected(protectedInput);protectedInput=""}){Text("Разрешить")}
        }
        Text("Один VPN-слот Android. Перед согласием другого VPN быть не должно: смена подготовленного провайдера может отозвать прежнее разрешение. Мы отказываемся при действующем VPN или Private DNS и не меняем их настройки.",style=MaterialTheme.typography.bodySmall)
        Text("Кэш, прямые IP, собственный DNS приложения, IPv6 и встроенный DoH/DoT могут обходить DNS-пробу. Перехватывается только IPv4 UDP DNS через указанный VPN-резолвер выбранных приложений. Приватные окна и все браузеры не объявляются защищёнными. После паузы/выхода кэш может задержать восстановление отдельных имён.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={
                if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
            }){Text("Разрешить уведомления")}
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("Настройки уведомлений")}
        }
        Row {
            Checkbox(checked=accepted,onCheckedChange={accepted=it})
            Text("Понимаю частичное покрытие и риск смены VPN-разрешения. Хочу применять выбранный снимок к следующим окнам защиты.",style=MaterialTheme.typography.bodySmall)
        }
        Text(if(state.setup.enabledForWindows)"Для будущих окон включено; это не доказательство активного VPN сейчас" else "Для будущих окон выключено",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={launch(controller.enableForFutureWindows(accepted))},enabled=accepted && !state.busy){Text("Разрешить для следующих окон")}
            TextButton(onClick=controller::disableFutureWindows){Text("Не включать в новых окнах")}
        }
        if(controller.hasFrozenWindow() && !state.runtime.active) {
            OutlinedButton(onClick={launch(controller.resumeCurrentWindow(accepted))},enabled=accepted && !state.busy){Text("Запросить запуск текущего снимка")}
        }
        if (state.runtime.active) TextButton(onClick = { confirmDnsStop = true }) { Text("Выключить только DNS-защиту") }
        if (confirmDnsStop) AlertDialog(onDismissRequest = { confirmDnsStop = false },
            title = { Text("Выключить DNS-защиту?") }, text = { Text("Сайты снова будут доступны через DNS. Ограничения приложений и окно останутся; VPN можно запустить снова из настроек.") },
            confirmButton = { TextButton(onClick = { confirmDnsStop = false; controller.stopCurrent() }) { Text("Выключить DNS") } },
            dismissButton = { TextButton(onClick = { confirmDnsStop = false }) { Text("Отмена") } })
        if(state.runtime.active || controller.hasFrozenWindow()) {
            Text("Остановка таймера не снимает окно. DNS можно отключить отдельно, либо открыть полный выход из защиты.",style=MaterialTheme.typography.bodySmall)
            if(onRequestFullExit!=null)TextButton(onClick=onRequestFullExit){Text("К полному выходу из защиты")}
        }
        Text(state.message,style=MaterialTheme.typography.bodySmall)
        Text(state.runtime.message,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DisclosureDomains(setup:DomainSetup,controller:DomainFilterController) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Скрыть точные имена" else "Выбрать отвлекающие имена · ${setup.blocked.size}")}
    if(expanded)(DomainSetup.PRESET + setup.blocked).sorted().forEach { domain ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(domain,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            Checkbox(domain in setup.blocked,{controller.selectDomain(domain,it)})
        }
    }
}
