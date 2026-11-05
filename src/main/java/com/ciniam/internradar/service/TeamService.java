package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.SysUserEntity;
import com.ciniam.internradar.entity.TeamEntity;
import com.ciniam.internradar.entity.TeamJoinRequestEntity;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.SysUserMapper;
import com.ciniam.internradar.mapper.TeamJoinRequestMapper;
import com.ciniam.internradar.mapper.TeamMapper;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.TeamInfo;
import com.ciniam.internradar.model.TeamJoinRequest;
import com.ciniam.internradar.model.TeamMember;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.model.UserStatus;
import com.ciniam.internradar.security.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 团队即租户。一个人只属于一个团队，加入靠邀请码，由团队创建人审批
 * 创建人自动成为该团队的管理员，其余成员默认实习生
 */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int NAME_LIMIT = 32;

    private final TeamMapper teamMapper;
    private final TeamJoinRequestMapper requestMapper;
    private final SysUserMapper sysUserMapper;
    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordVisibility visibility;
    // 未完成任务复用风险链路的判定，延迟注入避免构造期绕回
    private final RiskService riskService;
    private final SecureRandom random = new SecureRandom();

    public TeamService(TeamMapper teamMapper,
                       TeamJoinRequestMapper requestMapper,
                       SysUserMapper sysUserMapper,
                       WorkRecordMapper workRecordMapper,
                       WorkRecordVisibility visibility,
                       @Lazy RiskService riskService) {
        this.teamMapper = teamMapper;
        this.requestMapper = requestMapper;
        this.sysUserMapper = sysUserMapper;
        this.workRecordMapper = workRecordMapper;
        this.visibility = visibility;
        this.riskService = riskService;
    }

    /**
     * 创建团队。创建人直接成为管理员并入团
     */
    public TeamInfo create(String userId, String name, String description) {
        SysUserEntity user = requireUser(userId);
        if (teamIdOf(user) > 0) {
            throw new IllegalStateException("你已经属于一个团队，不能重复创建");
        }
        String teamName = name == null ? "" : name.trim();
        if (teamName.isEmpty()) {
            throw new IllegalArgumentException("团队名称不能为空");
        }
        if (teamName.length() > NAME_LIMIT) {
            throw new IllegalArgumentException("团队名称请控制在 " + NAME_LIMIT + " 个字符以内");
        }
        TeamEntity team = TeamEntity.builder()
                .name(teamName)
                .inviteCode(newInviteCode())
                .ownerUserId(userId)
                .description(description == null ? null : description.trim())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        teamMapper.insert(team);
        sysUserMapper.updateTeam(userId, team.getId(), UserRole.ADMIN.name(), null, LocalDateTime.now());
        log.info("团队已创建，团队 {}，创建人 {}", team.getId(), userId);
        return myTeam(userId);
    }

    /**
     * 用邀请码申请加入，落一条待审记录，等团队创建人处理
     */
    public TeamInfo join(String userId, String inviteCode, String message) {
        SysUserEntity user = requireUser(userId);
        if (teamIdOf(user) > 0) {
            throw new IllegalStateException("你已经属于一个团队");
        }
        String code = inviteCode == null ? "" : inviteCode.trim().toUpperCase();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("请填写邀请码");
        }
        TeamEntity team = teamMapper.selectByInviteCode(code);
        if (team == null) {
            throw new IllegalArgumentException("邀请码无效，请向团队创建人确认");
        }
        if (requestMapper.countPending(team.getId(), userId) > 0) {
            throw new IllegalStateException("你已经提交过申请，请等待团队创建人处理");
        }
        requestMapper.insert(TeamJoinRequestEntity.builder()
                .teamId(team.getId())
                .userId(userId)
                .status(STATUS_PENDING)
                .message(message == null ? null : message.trim())
                .createdAt(LocalDateTime.now())
                .build());
        log.info("收到加入团队申请，团队 {}，申请人 {}", team.getId(), userId);
        return myTeam(userId);
    }

    /**
     * 我的团队。未入团时只回带最近一次申请状态
     */
    public TeamInfo myTeam(String userId) {
        SysUserEntity user = requireUser(userId);
        long teamId = teamIdOf(user);
        if (teamId <= 0) {
            return new TeamInfo(null, null, null, null, null, false, 0, 0, latestJoinStatus(userId), null);
        }
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            return new TeamInfo(null, null, null, null, null, false, 0, 0, latestJoinStatus(userId), null);
        }
        boolean owner = userId.equals(team.getOwnerUserId());
        boolean manager = owner || UserRole.parse(user.getRole()) == UserRole.ADMIN;
        int pending = manager ? requestMapper.selectPendingByTeam(teamId).size() : 0;
        // 邀请码对团队成员都可见，方便任何人拉同事进来；审批仍然只归创建人与管理员
        return new TeamInfo(team.getId(), team.getName(), team.getInviteCode(),
                team.getDescription(), user.getRole(), owner,
                sysUserMapper.countByTeam(teamId), pending, null, team.getCreatedAt());
    }

    /**
     * 给没有创建人的团队补一个，启动期用在默认团队上
     */
    public void assignOwner(long teamId, String ownerUserId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null || team.getOwnerUserId() != null) {
            return;
        }
        teamMapper.updateOwner(teamId, ownerUserId, LocalDateTime.now());
        log.info("团队 {} 已补上创建人 {}", teamId, ownerUserId);
    }

    public List<TeamMember> members(String userId) {
        SysUserEntity user = requireUser(userId);
        long teamId = teamIdOf(user);
        if (teamId <= 0) {
            return List.of();
        }
        List<TeamMember> members = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        for (SysUserEntity entity : sysUserMapper.selectByTeam(teamId)) {
            // 团队页看的是别人的数据，产出按不含聊天摘要的口径统计
            List<WorkRecordEntity> records = workRecordMapper.selectByUserAndRange(entity.getUserId(),
                    start.atStartOfDay(), today.plusDays(1).atStartOfDay());
            members.add(new TeamMember(entity.getUserId(), entity.getName(), entity.getRole(),
                    UserStatus.parse(entity.getStatus()).pending(), entity.getEmail(),
                    visibility.countVisible(false, records),
                    riskService.openTasks(entity.getUserId(), today).size(),
                    workRecordMapper.selectLastOccurredAt(entity.getUserId())));
        }
        return members;
    }

    /**
     * 团队创建人看到的待审申请
     */
    public List<TeamJoinRequest> pendingRequests(String userId) {
        TeamEntity team = requireManagedTeam(userId);
        List<TeamJoinRequest> requests = new ArrayList<>();
        for (TeamJoinRequestEntity entity : requestMapper.selectPendingByTeam(team.getId())) {
            requests.add(toView(entity, team));
        }
        return requests;
    }

    public List<TeamJoinRequest> myRequests(String userId) {
        List<TeamJoinRequest> requests = new ArrayList<>();
        for (TeamJoinRequestEntity entity : requestMapper.selectByUser(userId)) {
            requests.add(toView(entity, teamMapper.selectById(entity.getTeamId())));
        }
        return requests;
    }

    /**
     * 通过申请并定角色，默认实习生，导师关系挂在审批人身上
     */
    public TeamJoinRequest approve(String operatorId, Long requestId, String role) {
        TeamJoinRequestEntity request = requireRequest(requestId);
        TeamEntity team = requireManagedTeam(operatorId, request.getTeamId());
        SysUserEntity applicant = requireUser(request.getUserId());
        if (teamIdOf(applicant) > 0) {
            throw new IllegalStateException("该用户已经加入了团队，无需再审批");
        }
        UserRole resolved = UserRole.parse(role);
        String mentor = resolved.canViewTeam() ? null : operatorId;
        sysUserMapper.updateTeam(applicant.getUserId(), team.getId(), resolved.name(), mentor,
                LocalDateTime.now());
        requestMapper.handle(requestId, STATUS_APPROVED, resolved.name(), operatorId, LocalDateTime.now());
        log.info("加入申请已通过，团队 {}，用户 {}，角色 {}", team.getId(), applicant.getUserId(), resolved);
        return toView(requestMapper.selectById(requestId), team);
    }

    public TeamJoinRequest reject(String operatorId, Long requestId) {
        TeamJoinRequestEntity request = requireRequest(requestId);
        TeamEntity team = requireManagedTeam(operatorId, request.getTeamId());
        requestMapper.handle(requestId, STATUS_REJECTED, null, operatorId, LocalDateTime.now());
        log.info("加入申请已拒绝，团队 {}，用户 {}", team.getId(), request.getUserId());
        return toView(requestMapper.selectById(requestId), team);
    }

    /**
     * 重新生成邀请码，旧码立即失效
     */
    public TeamInfo rotateInviteCode(String userId) {
        TeamEntity team = requireManagedTeam(userId);
        team.setInviteCode(newInviteCode());
        team.setUpdatedAt(LocalDateTime.now());
        teamMapper.updateInviteCode(team.getId(), team.getInviteCode(), team.getUpdatedAt());
        log.info("邀请码已更新，团队 {}", team.getId());
        return myTeam(userId);
    }

    private TeamEntity requireManagedTeam(String userId) {
        SysUserEntity user = requireUser(userId);
        long teamId = teamIdOf(user);
        if (teamId <= 0) {
            throw new ForbiddenException("你还没有加入团队");
        }
        return requireManagedTeam(userId, teamId);
    }

    private TeamEntity requireManagedTeam(String userId, long teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            throw new IllegalStateException("团队不存在：" + teamId);
        }
        SysUserEntity user = requireUser(userId);
        boolean owner = userId.equals(team.getOwnerUserId());
        boolean admin = UserRole.parse(user.getRole()) == UserRole.ADMIN;
        if (!owner && !admin) {
            throw new ForbiddenException("只有团队创建人可以审批加入申请");
        }
        return team;
    }

    private TeamJoinRequestEntity requireRequest(Long requestId) {
        TeamJoinRequestEntity request = requestId == null ? null : requestMapper.selectById(requestId);
        if (request == null) {
            throw new IllegalStateException("申请不存在：" + requestId);
        }
        if (!STATUS_PENDING.equals(request.getStatus())) {
            throw new IllegalStateException("该申请已经处理过了");
        }
        return request;
    }

    private SysUserEntity requireUser(String userId) {
        SysUserEntity user = userId == null ? null : sysUserMapper.selectByUserId(userId);
        if (user == null) {
            throw new IllegalStateException("账号不存在：" + userId);
        }
        return user;
    }

    private long teamIdOf(SysUserEntity user) {
        return user.getTeamId() == null ? TeamScope.NO_TEAM : user.getTeamId();
    }

    private String latestJoinStatus(String userId) {
        for (TeamJoinRequestEntity entity : requestMapper.selectByUser(userId)) {
            return entity.getStatus();
        }
        return null;
    }

    private TeamJoinRequest toView(TeamJoinRequestEntity entity, TeamEntity team) {
        if (entity == null) {
            return null;
        }
        String name = null;
        SysUserEntity user = sysUserMapper.selectByUserId(entity.getUserId());
        if (user != null) {
            name = user.getName() == null || user.getName().isBlank() ? user.getUserId() : user.getName();
        }
        return new TeamJoinRequest(entity.getId(), entity.getTeamId(),
                team == null ? null : team.getName(), entity.getUserId(), name,
                entity.getStatus(), entity.getMessage(), entity.getRole(), entity.getCreatedAt());
    }

    /**
     * 邀请码去掉容易看错的字母，6 位足够小团队使用
     */
    private String newInviteCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            String code = builder.toString();
            if (teamMapper.selectByInviteCode(code) == null) {
                return code;
            }
        }
        throw new IllegalStateException("邀请码生成失败，请重试");
    }

    /**
     * 供启动期初始化默认团队使用
     */
    public String randomInviteCode() {
        return newInviteCode();
    }

    /**
     * 当前用户是否可审批加入申请
     */
    public boolean canManage(SysUser user) {
        if (user == null || !user.joinedTeam()) {
            return false;
        }
        if (user.role() == UserRole.ADMIN) {
            return true;
        }
        TeamEntity team = teamMapper.selectById(user.teamId());
        return team != null && user.userId().equals(team.getOwnerUserId());
    }
}
