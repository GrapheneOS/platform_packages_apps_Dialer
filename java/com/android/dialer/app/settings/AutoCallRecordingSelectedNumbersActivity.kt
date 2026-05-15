// TODO: Migrate this screen off Dialer's deprecated platform settings stack.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.android.dialer.app.settings

import android.os.Bundle
import android.view.MenuItem
import com.android.dialer.app.R

/** Host activity for automatic call recording selected number settings. */
class AutoCallRecordingSelectedNumbersActivity : AppCompatPreferenceActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTitle(R.string.call_recording_auto_record_selected_numbers_title)
    if (savedInstanceState == null) {
      fragmentManager
          .beginTransaction()
          .replace(android.R.id.content, AutoCallRecordingSelectedNumbersFragment())
          .commit()
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      onBackPressed()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  override fun isValidFragment(fragmentName: String): Boolean =
      AutoCallRecordingSelectedNumbersFragment::class.java.name == fragmentName
}
