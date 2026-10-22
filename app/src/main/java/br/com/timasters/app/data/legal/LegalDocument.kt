package br.com.timasters.app.data.legal

import androidx.annotation.StringRes
import br.com.timasters.app.R

/**
 * Version of the Terms of Use + Privacy Policy the user accepts. Bump it whenever either text
 * changes materially; signed-in users are then asked to accept again (LGPD consent record).
 */
const val CURRENT_TERMS_VERSION = "2026-10-18"

enum class LegalDocument(
    @param:StringRes val titleResId: Int,
    @param:StringRes val bodyResId: Int
) {
    TERMS(R.string.legal_terms_title, R.string.legal_terms_body),
    PRIVACY(R.string.legal_privacy_title, R.string.legal_privacy_body)
}
