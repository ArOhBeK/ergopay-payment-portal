package org.ergoplatform.ergopay.paymentportal.rest

import org.ergoplatform.ergopay.paymentportal.config.EmbeddedPostgresManager
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping

@Controller
class AdminController(
    private val embeddedPostgresManager: EmbeddedPostgresManager
) {
    data class UpdatePasswordForm(
        var newPassword: String = "",
        var confirmPassword: String = ""
    )

    @GetMapping("/admin/database")
    fun showAdminPage(model: Model): String {
        val credentials = embeddedPostgresManager.currentCredentials()
        model.addAttribute("currentUser", credentials.username)
        model.addAttribute("form", UpdatePasswordForm())
        return "admin-database"
    }

    @PostMapping("/admin/database")
    fun updatePassword(
        @ModelAttribute("form") form: UpdatePasswordForm,
        model: Model,
    ): String {
        val errors = mutableListOf<String>()
        if (form.newPassword.length < 8) {
            errors += "Password must be at least 8 characters long."
        }
        if (form.newPassword != form.confirmPassword) {
            errors += "Passwords do not match."
        }

        if (errors.isEmpty()) {
            embeddedPostgresManager.changePassword(form.newPassword)
            model.addAttribute("successMessage", "Password updated. New credentials stored locally.")
            model.addAttribute("form", UpdatePasswordForm())
        } else {
            model.addAttribute("errors", errors)
        }

        model.addAttribute("currentUser", embeddedPostgresManager.currentCredentials().username)
        return "admin-database"
    }
}
