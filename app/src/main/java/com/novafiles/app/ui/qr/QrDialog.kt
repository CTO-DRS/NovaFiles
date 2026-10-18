package com.novafiles.app.ui.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.novafiles.app.R
import com.novafiles.app.core.qr.QrCode
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.delay

/**
 * حوار مشاركة QR بنمط iOS — يعرض الرمز ورابط الوصول ونسخه.
 * الرابط يُولَّد مسبقاً ويُمرَّر جاهزاً؛ الحوار مسؤول عن العرض والنسخ فقط.
 */
@Composable
fun QrDialog(
    title: String,
    url: String,
    hint: String,
    onDismiss: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    // الصورة تُبنى مرة واحدة لكل رابط — خارج مسار إعادة التجميع
    val bitmap = remember(url) { runCatching { QrCode.bitmap(url, 640) }.getOrNull() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (p == com.novafiles.app.ui.ios.IOSDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
            modifier = Modifier.width(300.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(18.dp))
                Text(
                    title,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = p.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))

                // بطاقة الرمز — خلفية بيضاء دائماً لتبقى الرموز قابلة للمسح في الوضع الداكن
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(228.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.qr_failed),
                            style = TextStyle(fontSize = 14.sp),
                            color = p.secondaryLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    hint,
                    style = TextStyle(fontSize = 13.sp),
                    color = p.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))

                // شريط الرابط مع زر النسخ
                Row(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .background(p.fill, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        url,
                        style = TextStyle(fontSize = 12.sp),
                        color = p.accent,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.ftp_copy_url),
                        tint = if (copied) p.green else p.accent,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                clipboard.setText(AnnotatedString(url))
                                copied = true
                            },
                    )
                }
                Spacer(Modifier.height(14.dp))

                // زر إغلاق iOS
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(p.separator),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.action_ok),
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = p.accent,
                    )
                }
            }
        }
    }
}
