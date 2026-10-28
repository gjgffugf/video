package com.zilizili.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var etAccount: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnGoRegister: Button
    private lateinit var tvMsg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etAccount = findViewById(R.id.etAccount)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoRegister = findViewById(R.id.btnGoRegister)
        tvMsg = findViewById(R.id.tvMsg)

        btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }

        btnLogin.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val acct = etAccount.text.toString().trim()
        val pw = etPassword.text.toString()
        if (acct.isEmpty() || pw.isEmpty()) {
            tvMsg.text = "请填写账号和密码"
            return
        }
        btnLogin.isEnabled = false
        tvMsg.text = ""
        lifecycleScope.launch {
            val (ok, err) = Api.login(acct, pw)
            btnLogin.isEnabled = true
            if (ok) {
                val u = Api.lastUser
                if (u != null) Session.save(this@LoginActivity, Api.lastToken, u)
                setResult(RESULT_OK)
                finish()
            } else {
                tvMsg.text = err
            }
        }
    }
}
