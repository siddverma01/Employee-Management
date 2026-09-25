package com.emplmgt.service;

import com.emplmgt.dto.AuthDtos;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.User;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.JwtService;
import com.emplmgt.util.AppClock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AppClock appClock;

    @Transactional
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email().trim(), request.password()));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw ApiException.unauthorized("Your account has been deactivated");
        }
        user.setLastLoginAt(appClock.now());
        userRepository.save(user);
        userRepository.updateLastLogin(user.getId(), appClock.now());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return new AuthDtos.LoginResponse(token, jwtService.getExpirationMs(), toUserDto(user));
    }

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        AuthDtos.UserDto dto = toUserDto(user);
        return new AuthDtos.MeResponse(dto.id(), dto.email(), dto.role(),
                dto.employeeCode(), dto.fullName(), dto.avatar(), dto.employeeId(),
                dto.teamId(), dto.teamName());
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserDto toUserDto(User user) {
        String fullName = null;
        String employeeCode = null;
        String avatar = null;
        Long employeeId = null;
        Long teamId = null;
        String teamName = null;
        Employee employee = employeeRepository.findByUserId(user.getId()).orElse(null);
        if (employee != null) {
            fullName = employee.getFullName();
            employeeCode = employee.getEmployeeCode();
            avatar = employee.getProfilePicture();
            employeeId = employee.getId();
            if (employee.getDepartment() != null) {
                teamId = employee.getDepartment().getId();
                teamName = employee.getDepartment().getName();
            }
        }
        return new AuthDtos.UserDto(user.getId(), user.getEmail(), user.getRole().name(),
                employeeCode, fullName, avatar, employeeId, teamId, teamName);
    }
}