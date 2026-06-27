package com.fesu.renjana.gms

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fesu.renjana.RenjanaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AccountPickerActivity - Shows UI for selecting Google account
 */
class AccountPickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INSTANCE_ID = GoogleSignInVirtualizer.EXTRA_INSTANCE_ID
        const val EXTRA_SELECTED_ACCOUNT_ID = GoogleSignInVirtualizer.EXTRA_SELECTED_ACCOUNT_ID
    }

    private lateinit var instanceId: String
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Simple layout
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Select Google Account"
            textSize = 20f
        }
        layout.addView(title)

        val listView = ListView(this)
        layout.addView(listView)

        setContentView(layout)

        // Load accounts
        scope.launch {
            val accountManager = RenjanaApplication.get().googleAccountManager
            accountManager.getAllAccounts().collect { accounts ->
                val adapter = ArrayAdapter(
                    this@AccountPickerActivity,
                    android.R.layout.simple_list_item_1,
                    accounts.map { it.email }
                )
                listView.adapter = adapter

                listView.setOnItemClickListener { _, _, position, _ ->
                    val selectedAccount = accounts[position]
                    val resultIntent = android.content.Intent().apply {
                        putExtra(EXTRA_SELECTED_ACCOUNT_ID, selectedAccount.id)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
    }
}
