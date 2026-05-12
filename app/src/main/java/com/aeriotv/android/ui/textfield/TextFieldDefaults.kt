package com.aeriotv.android.ui.textfield

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Default KeyboardOptions for every text field in the app. Samsung Keyboard's
 * predictive-text composition pathway breaks IME → TextField commits when
 * the field starts empty: typing produces no characters, but pasting works,
 * and once the field has any content typing starts behaving. Setting
 * `autoCorrectEnabled = false` sidesteps composition mode entirely and
 * routes every keystroke as a direct commit.
 *
 * Callers that want a tailored IME (Uri, password, number) pass the type
 * + a sensible default action. Hex and numeric inputs also opt out of the
 * IME's predictive bar via this same helper.
 */
@Composable
fun aerioTextFieldKeyboardOptions(
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
): KeyboardOptions = KeyboardOptions(
    keyboardType = keyboardType,
    imeAction = imeAction,
    capitalization = capitalization,
    autoCorrectEnabled = false,
)
