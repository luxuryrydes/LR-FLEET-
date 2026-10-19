package com.lrtaxi.fleet.users;

import com.lrtaxi.fleet.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize(Roles.ADMIN)
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    public record CreateUser(@NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 200) String fullName,
                             @NotBlank String password, @NotEmpty List<String> roles) {
    }

    public record UpdateUser(@Size(max = 200) String fullName, Boolean active, List<String> roles) {
    }

    public record ResetPassword(@NotBlank String password) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return users.list();
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateUser req) {
        return Map.of("id", users.create(req.email(), req.fullName(), req.password(), req.roles()));
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @Valid @RequestBody UpdateUser req) {
        users.update(id, req.fullName(), req.active(), req.roles());
    }

    @PostMapping("/{id}/reset-password")
    public void reset(@PathVariable long id, @Valid @RequestBody ResetPassword req) {
        users.resetPassword(id, req.password());
    }
}
