package com.bydhud.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MailControlButton(language: Language, p: Palette) {
    val mail = DeveloperMailbox
    var open by rememberSaveable { mutableStateOf(false) }
    val anchor = LocalView.current
    DisposableEffect(anchor) {
        val lifecycle = anchor.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            mail.visible(anchor, lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true)
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); mail.visible(null, false) }
    }
    if (open) MailDialog(mail, language, p) { open = false }
    val unread = mail.unread
    val label = language.choose("Вхідні", "Inbox", "Входящие")
    Box {
        HudIconButton(R.drawable.ic_mail_closed, if (unread == 0) label else "$label ($unread)",
            p, p.accent, onClick = { open = true })
        if (unread > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
            .size(12.dp).background(p.red, CircleShape).border(2.dp, p.panel, CircleShape))
    }
}

private enum class MailFolder { Inbox, Sent, Trash }
private enum class MailPage { List, Message, Compose, Settings }
private val mailTimestamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT)

private fun mailDate(value: Long): String =
    mailTimestamp.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))

@Composable
internal fun MailDialog(mail: DeveloperMailbox, language: Language, p: Palette, onClose: () -> Unit) {
    fun t(uk: String, en: String, ru: String) = language.choose(uk, en, ru)
    val inbox = t("Вхідні", "Inbox", "Входящие")
    val sent = t("Вихідні", "Sent", "Исходящие")
    val trash = t("Кошик", "Trash", "Корзина")
    val settings = t("Налаштування пошти", "Mail settings", "Настройки почты")
    val compose = t("Написати", "Compose", "Написать")
    val developer = t("Розробник BYD HUD", "BYD HUD developer", "Разработчик BYD HUD")
    val close = t("Закрити", "Close", "Закрыть")
    var folder by rememberSaveable { mutableStateOf(MailFolder.Inbox) }
    var page by rememberSaveable { mutableStateOf(MailPage.List) }
    var selected by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    val letter = mail.letters.find { it.id == selected }
    val ctx = LocalContext.current
    fun back() { if (page == MailPage.List) onClose() else page = MailPage.List }
    fun draft(title: String) { subject = title; body = ""; page = MailPage.Compose }

    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView=LocalView.current
        DisposableEffect(dialogView) {
            mail.dialog(dialogView)
            onDispose { mail.dialog(null) }
        }
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(.94f).fillMaxHeight(.92f)
            .clip(RoundedCornerShape(10.dp)).background(p.surface)
            .border(1.dp, p.borderStrong, RoundedCornerShape(10.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudIconButton(if (page == MailPage.List) android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_mail_back,
                    if (page == MailPage.List) close else t("Назад", "Back", "Назад"), p, p.muted, onClick = { back() })
                Text(when (page) {
                    MailPage.Settings -> settings
                    MailPage.Compose -> t("Новий лист", "New message", "Новое письмо")
                    MailPage.Message -> t("Лист", "Message", "Письмо")
                    MailPage.List -> t("Пошта", "Mail", "Почта")
                }, Modifier.weight(1f), fontSize = 22.sp, color = p.text, fontWeight = FontWeight.Bold)
                if (page == MailPage.List) HudIconButton(R.drawable.ic_options_settings, settings, p, p.accent) { page = MailPage.Settings }
            }
            when (page) {
                MailPage.List -> {
                    var checked by remember(folder) { mutableStateOf(emptySet<String>()) }
                    var pendingPurge by remember(folder) { mutableStateOf<Set<String>?>(null) }
                    val inTrash = folder == MailFolder.Trash
                    val visible = mail.letters.filter { when (folder) {
                        MailFolder.Inbox -> !it.sent && !it.trash
                        MailFolder.Sent -> it.sent && !it.trash
                        MailFolder.Trash -> it.trash
                    } }.asReversed()
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Segmented(left = if (mail.unread == 0) inbox else "$inbox (${mail.unread})", right = sent,
                            leftActive = folder == MailFolder.Inbox, palette = p, itemWidth = 110.dp,
                            onLeft = { folder = MailFolder.Inbox }, onRight = { folder = MailFolder.Sent },
                            third = trash, thirdActive = folder == MailFolder.Trash, onThird = { folder = MailFolder.Trash })
                        Spacer(Modifier.weight(1f))
                        if (folder == MailFolder.Inbox) MailButton(t("Отримати", "Receive", "Получить"),
                            p, false, enabled = mail.active && !mail.busy, icon = R.drawable.ic_mail_receive) {
                            mail.receive()
                        }
                        if (folder != MailFolder.Trash) MailButton(compose, p, true, enabled = mail.active) { draft("") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val selection = when {
                            checked.isEmpty() -> ToggleableState.Off
                            visible.all { it.id in checked } -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        val enabled = visible.isNotEmpty()
                        Row(Modifier.width(152.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (enabled) p.panelAlt else p.disabled).border(1.dp, p.borderStrong, RoundedCornerShape(7.dp))
                            .triStateToggleable(state = selection, enabled = enabled, role = Role.Checkbox) {
                                checked = if (selection == ToggleableState.On) emptySet() else visible.map { it.id }.toSet()
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                                TriStateCheckbox(state = selection, onClick = null, enabled = enabled,
                                    colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.muted, checkmarkColor = Color.White))
                            }
                            Text(t("Вибрати все", "Select all", "Выбрать всё"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = if (enabled) p.text else p.muted)
                        }
                        MailButton(t("Видалити вибране", "Delete selected", "Удалить выбранное"), p, false, Modifier.width(188.dp),
                            enabled = checked.isNotEmpty(), icon = R.drawable.ic_delete, destructive = true) {
                            if (inTrash) pendingPurge = checked
                            else { mail.trash(checked, true); checked = emptySet() }
                        }
                        if (inTrash) MailButton(t("Відновити вибране", "Restore selected", "Восстановить выбранное"),
                            p, false, enabled = checked.isNotEmpty(), icon = R.drawable.ic_mail_restore) {
                            mail.trash(checked, false)
                            checked = emptySet()
                        }
                        if (folder == MailFolder.Inbox) {
                            HudIconButton(R.drawable.ic_mail_open, t("Позначити як прочитане", "Mark as read", "Отметить как прочитанное"),
                                p, p.accent, modifier = Modifier.size(48.dp), enabled = visible.any { it.id in checked && !it.read }) {
                                mail.setRead(checked, true)
                            }
                            HudIconButton(R.drawable.ic_mail_closed, t("Позначити як непрочитане", "Mark as unread", "Отметить как непрочитанное"),
                                p, p.accent, modifier = Modifier.size(48.dp), enabled = visible.any { it.id in checked && it.read }) {
                                mail.setRead(checked, false)
                            }
                        }
                        if (checked.isNotEmpty()) Text(t("Вибрано: ", "Selected: ", "Выбрано: ") + checked.size,
                            color = p.muted, fontSize = 14.sp)
                    }
                    pendingPurge?.let { ids ->
                        AlertDialog(onDismissRequest = { pendingPurge = null },
                            title = { Text(t("Видалити вибрані листи?", "Delete selected messages?", "Удалить выбранные письма?")) },
                            text = { Text(t("Буде видалено безповоротно: ", "Permanently deleting: ", "Будет удалено безвозвратно: ") + ids.size) },
                            confirmButton = { TextButton(onClick = {
                                mail.deleteFromTrash(ids); checked = emptySet(); pendingPurge = null
                            }) { Text(t("Видалити", "Delete", "Удалить"), color = p.red) } },
                            dismissButton = { TextButton(onClick = { pendingPurge = null }) { Text(t("Скасувати", "Cancel", "Отмена")) } },
                            containerColor = p.surface, titleContentColor = p.text, textContentColor = p.muted)
                    }
                    if (!mail.active) {
                        Column(Modifier.fillMaxWidth().background(p.panelAlt, RoundedCornerShape(7.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(if (mail.id.isEmpty()) t("Для листування з розробником потрібна твоя згода.", "Give consent to exchange mail with the developer.", "Для переписки с разработчиком нужно твоё согласие.")
                                else t("Згоду відкликано. Збережені листи доступні.", "Consent withdrawn. Your saved mail is available.", "Согласие отозвано. Сохранённые письма доступны."), color = p.text)
                            MailButton(if (mail.id.isEmpty()) t("Підключити пошту", "Set up mail", "Подключить почту")
                                else t("Відновити листування", "Resume mail", "Возобновить переписку"), p, true) { page = MailPage.Settings }
                        }
                    }
                    if (visible.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(when (folder) {
                            MailFolder.Inbox -> t("Вхідних листів немає", "No incoming messages", "Входящих писем нет")
                            MailFolder.Sent -> t("Надісланих листів немає", "No sent messages", "Отправленных писем нет")
                            MailFolder.Trash -> t("Кошик порожній", "Trash is empty", "Корзина пуста")
                        }, color = p.muted)
                    } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(visible, key = { it.id }) { item ->
                            val unread = !item.sent && !item.read
                            val weight = if (unread) FontWeight.Bold else FontWeight.Normal
                            val secondary = if (unread) p.text else p.muted
                            val rowColor = when {
                                unread -> lerp(p.panelAlt, p.accent, if (p.dark) 0.34f else 0.23f)
                                item.id in checked -> p.active
                                else -> p.panelAlt
                            }
                            val sender = if (item.announcement) t("Оголошення", "Announcement", "Объявление") else developer
                            val detail = if (folder == MailFolder.Trash) {
                                (if (item.sent) t("Вихідний", "Outgoing", "Исходящее") else t("Вхідний", "Incoming", "Входящее")) + " · " + sender
                            } else if (item.sent) t("Кому: ", "To: ", "Кому: ") + sender else sender
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(rowColor), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.id in checked,
                                    onCheckedChange = { checked = if (it) checked + item.id else checked - item.id },
                                    modifier = Modifier.semantics { contentDescription = t("Вибрати лист: ", "Select message: ", "Выбрать письмо: ") + item.subject },
                                    colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.muted, checkmarkColor = Color.White))
                                Row(Modifier.weight(1f)
                                    .clickable(role = Role.Button) {
                                        selected = item.id
                                        if (!item.read) mail.setRead(setOf(item.id), true)
                                        page = MailPage.Message
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Box(Modifier.size(8.dp).background(if (unread) p.red else Color.Transparent, CircleShape))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.subject, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = weight)
                                        val status = when (item.delivery) {
                                            "queued" -> t("Очікує надсилання", "Queued", "Ожидает отправки")
                                            "held" -> t("Надсилання не підтверджено", "Sending unconfirmed", "Отправка не подтверждена")
                                            else -> ""
                                        }
                                        Text(listOf(detail, status).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 13.sp, color = secondary, fontWeight = weight)
                                    }
                                    Text(mailDate(item.date), color = secondary, fontSize = 13.sp, fontWeight = weight)
                                }
                            }
                        }
                    }
                }
                MailPage.Message -> if (letter != null) {
                    Text(letter.subject, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((if (letter.sent) t("Кому: ", "To: ", "Кому: ") else t("Від: ", "From: ", "От: ")) + developer + "   ·   " + mailDate(letter.date),
                        color = p.muted, fontSize = 14.sp)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(letter.body, color = p.text, fontSize = 17.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!letter.sent && !letter.trash && !letter.announcement) MailButton(t("Відповісти", "Reply", "Ответить"),
                            p, true, enabled = mail.active) { draft("Re: " + letter.subject.removePrefix("Re: ")) }
                        MailButton(if (letter.trash) t("Відновити", "Restore", "Восстановить")
                            else t("У кошик", "Move to trash", "В корзину"), p, false) {
                            mail.trash(setOf(letter.id), !letter.trash); page = MailPage.List
                        }
                    }
                }
                MailPage.Compose -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    val wait = mail.waitMillis(now)
                    LaunchedEffect(wait > 0) {
                        while (mail.waitMillis(System.currentTimeMillis()) > 0) { delay(1000L); now = System.currentTimeMillis() }
                        now = System.currentTimeMillis()
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("Кому: ", "To: ", "Кому: ") + developer, color = p.muted)
                        MailField(subject, { if (it.codePointCount(0, it.length) <= 200) subject = it }, t("Тема", "Subject", "Тема"), p, true)
                        MailField(body, { if (it.codePointCount(0, it.length) <= 4000) body = it },
                            t("Текст листа", "Message body", "Текст письма"), p, false)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MailButton(t("Надіслати", "Send", "Отправить"), p, true,
                            enabled = mail.active && wait == 0L && subject.isNotBlank() && body.isNotBlank()) {
                            mail.send(subject, body) { page = MailPage.List; folder = MailFolder.Sent }
                        }
                        Text("${body.codePointCount(0, body.length)} / 4000", color = p.muted)
                        if (wait > 0) {
                            val seconds = (wait + 999) / 1000
                            Text(t("Наступний лист через $seconds с", "Send again in $seconds s", "Следующее письмо через $seconds с"), color = p.muted)
                        }
                    }
                }
                MailPage.Settings -> Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var nickname by rememberSaveable(mail.nickname) { mutableStateOf(mail.nickname) }
                    var description by rememberSaveable(mail.vehicleDescription) { mutableStateOf(mail.vehicleDescription) }
                    Column(Modifier.fillMaxWidth().background(p.panelAlt, RoundedCornerShape(7.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("Листування з розробником", "Mail with the developer", "Переписка с разработчиком"), color = p.text,
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(t("При підключенні застосунок передає DiLink ID для прив’язки до авто та спільного ліміту. Розробник побачить твій нік, ID скриньки та опис авто, якщо ти його додаси. Згоду можна відкликати, зберігши листи.",
                            "Setup sends your DiLink ID for vehicle binding and a shared quota. The developer will see your nickname, mailbox ID and any vehicle description you add. You can withdraw consent and keep your messages.",
                            "При подключении приложение передаёт DiLink ID для привязки к авто и общей квоты. Разработчик увидит твой ник, ID ящика и описание авто, если ты его добавишь. Согласие можно отозвать, сохранив письма."), color = p.muted)
                        if (mail.nickname.isEmpty()) {
                            MailField(nickname, { if (it.codePointCount(0, it.length) <= DeveloperMailbox.MAX_NICKNAME) nickname = it },
                                t("Нік (обов’язково)", "Nickname (required)", "Ник (обязательно)"), p, true)
                            MailField(description, { if (it.codePointCount(0, it.length) <= DeveloperMailbox.MAX_DESCRIPTION) description = it },
                                t("Опис авто (необов’язково)", "Vehicle description (optional)", "Описание авто (необязательно)"), p, true)
                        } else {
                            Text(listOf(mail.nickname, mail.vehicleDescription).filter { it.isNotEmpty() }.joinToString(" · "),
                                color = p.text, fontWeight = FontWeight.SemiBold)
                        }
                        if (mail.id.isNotEmpty()) Text("ID: " + mail.id, color = p.text, fontSize = 14.sp)
                        if (!mail.active || mail.nickname.isEmpty()) {
                            MailButton(when {
                                mail.active -> t("Зберегти профіль", "Save profile", "Сохранить профиль")
                                mail.id.isEmpty() -> t("Згенерувати ID і передати розробнику", "Generate ID and share with developer", "Создать ID и передать разработчику")
                                else -> t("Відновити згоду", "Renew consent", "Возобновить согласие")
                            }, p, true, enabled = mail.ready && !mail.signupPending && nickname.isNotBlank()) {
                                mail.confirmProfile(nickname, description)
                            }
                        }
                        if (mail.active) MailButton(t("Відкликати згоду", "Withdraw consent", "Отозвать согласие"), p, false) {
                            mail.revoke { page = MailPage.List; folder = MailFolder.Inbox }
                        }
                    }
                    MailSwitch(t("Загальні оголошення", "Developer announcements", "Общие объявления"),
                        t("Отримувати розсилки розробника для всіх підписаних. Особисті листи надходять незалежно від цієї опції.",
                            "Receive developer announcements sent to all subscribers. Personal messages arrive regardless of this setting.",
                            "Получать рассылки разработчика для всех подписавшихся. Личные письма приходят независимо от этой настройки."),
                        mail.announcements, mail.active, p) { mail.setNewsEnabled(it) }
                    MailSwitch(t("Спливаючий віджет-вікно вхідного листа зверху", "Incoming mail pop-up at the top of the screen", "Всплывающее виджет-окно входящего письма сверху"),
                        t("Торкніться спливаючого вікна, щоб прибрати його. Лист залишиться непрочитаним у «Вхідних».",
                            "Tap the pop-up to dismiss it. The message stays unread in your inbox.",
                            "Коснитесь всплывающего окна, чтобы убрать его. Письмо останется непрочитанным во входящих."),
                        mail.banner, mail.active, p) { mail.setBannerEnabled(it); if (!it) MailBanner.dismiss() }
                    if (mail.active && mail.banner && !Settings.canDrawOverlays(ctx)) {
                        MailButton(t("Дозволити показ поверх застосунків", "Allow display over other apps", "Разрешить показ поверх приложений"), p, false) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                        }
                    }
                }
            }
            val pending = when {
                mail.signupPending -> t("Підключаємо пошту…", "Connecting mail…", "Подключаем почту…")
                mail.id.isNotEmpty() && !mail.active && mail.controlPending ->
                    t("Обмін зупинено на пристрої. Очікуємо підтвердження відкликання від сервера.",
                      "Exchange stopped on this device. Waiting for the server to confirm withdrawal.",
                      "Обмен остановлен на устройстве. Ожидаем подтверждения отзыва сервером.")
                mail.active && mail.state?.revision == 0L ->
                    t("Очікуємо реєстрації скриньки на сервері.", "Waiting for mailbox registration.", "Ожидаем регистрации ящика на сервере.")
                else -> ""
            }
            val feedback = listOf(pending, mail.status).filter { it.isNotBlank() }.joinToString(" · ")
            if (feedback.isNotEmpty()) Text(feedback, color = p.muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MailSwitch(label: String, hint: String, value: Boolean, enabled: Boolean, p: Palette, change: (Boolean) -> Unit) {
    val control = remember { mutableStateOf<SwitchExternalControl?>(null) }
    ActionRow(label, hint, p, enabled = enabled, toggleChecked = value, onToggle = change, switchControl = control) {
        HudSwitch(value, change, p, enabled = enabled, externalControl = control)
    }
}

@Composable
private fun MailField(value: String, change: (String) -> Unit, label: String, p: Palette, single: Boolean) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = single,
        minLines = if (single) 1 else 3, maxLines = if (single) 1 else 6,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = p.text, unfocusedTextColor = p.text,
            focusedLabelColor = p.accent, unfocusedLabelColor = p.muted, unfocusedBorderColor = p.borderStrong,
            focusedBorderColor = p.accent, cursorColor = p.accent))
}


@Composable
private fun MailButton(text: String, p: Palette, primary: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: Int? = null, destructive: Boolean = false, onClick: ()->Unit) {
    val tint = when {
        !enabled -> p.muted
        destructive -> p.red
        primary && p.dark -> Color.White
        else -> p.text
    }
    val shape = RoundedCornerShape(7.dp)
    Row(modifier.height(44.dp).clip(shape)
        .background(when { !enabled -> p.disabled; destructive -> p.redSoft; primary -> p.accent.copy(alpha=if(p.dark) .78f else .08f); else -> p.panelAlt })
        .border(1.dp, if(destructive) p.red else if(primary) p.accent else p.borderStrong,shape)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        if(icon!=null) { Icon(painterResource(icon),null,Modifier.size(20.dp),tint=tint); Spacer(Modifier.width(6.dp)) }
        Text(text,color=tint,fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1)
    }
}
