package com.emplmgt.service;

import com.emplmgt.entity.Notification;
import com.emplmgt.entity.NotificationType;
import com.emplmgt.entity.User;
import com.emplmgt.repository.NotificationRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.util.AppClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AppClock appClock;
    private final com.emplmgt.repository.HolidayRepository holidayRepository;
    private final com.emplmgt.repository.EmployeeRepository employeeRepository;

    public List<Notification> findForUser(Long userId, int page, int size) {
        return notificationRepository.findAllForUser(userId, org.springframework.data.domain.PageRequest.of(page, size))
                .getContent();
    }

    public long unreadCount(Long userId) {
        return notificationRepository.countUnreadForUser(userId);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllReadForUser(userId, appClock.now());
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        var existing = notificationRepository.findById(notificationId)
                .orElseThrow(() -> com.emplmgt.exception.ApiException.notFound("Notification not found"));
        if (existing.getUser() != null && !existing.getUser().getId().equals(userId)) {
            throw com.emplmgt.exception.ApiException.forbidden("Not your notification");
        }
        existing.setReadAt(appClock.now());
        notificationRepository.save(existing);
    }

    public void notifyUser(Long userId, String title, String body, NotificationType type, String link) {
        notificationRepository.save(Notification.builder()
                .user(userRepository.findById(userId).orElse(null))
                .title(title)
                .body(body)
                .type(type)
                .link(link)
                .createdAt(appClock.now())
                .build());
    }

    public void notifyAdmins(String title, String body, NotificationType type, String link) {
        List<User> admins = userRepository.findByRole(com.emplmgt.entity.Role.ADMIN);
        for (User admin : admins) {
            notificationRepository.save(Notification.builder()
                    .user(admin)
                    .title(title)
                    .body(body)
                    .type(type)
                    .link(link)
                    .createdAt(appClock.now())
                    .build());
        }
    }

    public void broadcast(String title, String body, NotificationType type, String link) {
        notificationRepository.save(Notification.builder()
                .user(null)
                .title(title)
                .body(body)
                .type(type)
                .link(link)
                .createdAt(appClock.now())
                .build());
    }

    /**
     * Runs hourly (dev-friendly interval). Scans for upcoming holidays and birthdays and fires
     * per-user notifications once per occurrence for active employees.
     */
    @Scheduled(cron = "${application.notification.check-cron:0 0 */1 * * *}")
    public void generateUpcomingNotifications() {
        try {
            LocalDate today = appClock.today();
            int holidayWindow = 3;
            int birthdayWindow = 3;

            boolean hasEvents = holidayRepository.countByHolidayDateBetween(today.plusDays(1), today.plusDays(holidayWindow)) > 0;
            if (hasEvents) {
                holidayRepository.findByHolidayDateBetween(today, today.plusDays(holidayWindow))
                        .forEach(h -> {
                            String key = "holiday-" + h.getId() + "-" + today;
                            String markerBody = "#" + key + "# | " + h.getHolidayDate() + " - "
                                    + (h.getDescription() == null || h.getDescription().isBlank() ? "Enjoy your day off!" : h.getDescription());
                            if (!notificationRepository.existsBroadcastLike(key)) {
                                broadcast("Upcoming Holiday: " + h.getName(),
                                        markerBody,
                                        NotificationType.HOLIDAY, "/holidays");
                            }
                        });
            }

            employeeRepository.findByEmploymentStatus(com.emplmgt.entity.EmploymentStatus.ACTIVE)
                    .stream()
                    .filter(e -> e.getDateOfBirth() != null)
                    .filter(e -> isWithinWindow(e.getDateOfBirth(), today, birthdayWindow))
                    .forEach(e -> {
                        if (e.getUser() == null) {
                            return;
                        }
                        LocalDate bd = e.getDateOfBirth();
                        LocalDate thisYear = LocalDate.of(today.getYear(), bd.getMonth(), daySafe(bd, today.getYear()));
                        String key = "birthday-" + e.getId() + "-" + thisYear;
                        if (thisYear.isAfter(today) && thisYear.isBefore(today.plusDays(birthdayWindow + 1))) {
                            if (!notificationRepository.existsBroadcastLike(key)) {
                                broadcast("Birthday: " + e.getFullName(),
                                        "#" + key + "# | " + e.getFullName() + " celebrates their birthday on " + thisYear + "!",
                                        NotificationType.BIRTHDAY, "/calendar");
                            }
                        }
                    });
        } catch (Exception ex) {
            log.warn("Notification generation failed: {}", ex.getMessage());
        }
    }

    private boolean isWithinWindow(LocalDate dob, LocalDate today, int window) {
        LocalDate candidate;
        try {
            candidate = LocalDate.of(today.getYear(), dob.getMonthValue(), dob.getDayOfMonth());
        } catch (Exception e) {
            return false;
        }
        return !candidate.isBefore(today) && !candidate.isAfter(today.plusDays(window));
    }

    private int daySafe(LocalDate date, int year) {
        try {
            LocalDate.of(year, date.getMonthValue(), date.getDayOfMonth());
            return date.getDayOfMonth();
        } catch (Exception e) {
            return Math.min(date.getDayOfMonth(),
                    LocalDate.of(year, date.getMonthValue(), 1).lengthOfMonth());
        }
    }
}