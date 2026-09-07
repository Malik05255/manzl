@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package androidx.compose.foundation.layout

import androidx.compose.ui.unit.Dp

/**
 * Compatibility symbol for Compose versions where PaddingValues.calculateBottomPadding is a
 * member (and therefore normally requires no import). The reference launcher imports the symbol
 * explicitly; this zero-behavior extension keeps that source portable while the member remains the
 * implementation selected by Kotlin.
 */
fun PaddingValues.calculateBottomPadding(): Dp = this.calculateBottomPadding()
