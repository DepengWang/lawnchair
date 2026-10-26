package app.lawnchair.smartspace

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import com.android.launcher3.R

class HealthPermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.health_permissions_rationale_title)
            .setMessage(R.string.health_permissions_rationale_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
