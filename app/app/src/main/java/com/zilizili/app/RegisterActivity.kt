package com.zilizili.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etPw: EditText
    private lateinit var etPw2: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnGoLogin: Button
    private lateinit var tvMsg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        Api.init(this)
        etName = findViewById(R.id.etName)
        etPw = findViewById(R.id.etPw)
        etPw2 = findViewById(R.id.etPw2)
        btnRegister = findViewById(R.id.btnRegister)
        btnGoLogin = findViewById(R.id.btnGoLogin)
        tvMsg = findViewById(R.id.tvMsg)

        btnGoLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnRegister.setOnClickListener { doRegister() }
    }

    private fun doRegister() {
        val name = etName.text.toString().trim()
        val pw = etPw.text.toString()
        val pw2 = etPw2.text.toString()

        if (name.isEmpty()) { tvMsg.text = "请填写用户名"; return }
        if (pw.length < 6) { tvMsg.text = "密码至少 6 位"; return }
        if (pw != pw2) { tvMsg.text = "两次密码不一致"; return }

        btnRegister.isEnabled = false
        tvMsg.text = ""

        lifecycleScope.launch {
            val (ok, err) = Api.register(name, pw)
            btnRegister.isEnabled = true
            if (ok) {
                val u = Api.lastUser
                if (u != null) Session.save(this@RegisterActivity, Api.lastToken, u)
                setResult(RESULT_OK)
                finish()
            } else {
                tvMsg.text = err
            }
        }
    }
}
