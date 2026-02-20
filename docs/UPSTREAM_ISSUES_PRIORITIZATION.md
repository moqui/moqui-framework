# Upstream moqui/moqui-framework Issues & PRs Prioritization

**Generated**: 2025-12-07
**Repository**: [moqui/moqui-framework](https://github.com/moqui/moqui-framework)
**Fork**: [hunterino/moqui](https://github.com/hunterino/moqui)

## Executive Summary

This document provides a comprehensive analysis and prioritization of open issues and pull requests in the upstream `moqui/moqui-framework` repository. The goal is to align contributions with the project mission and identify items for closure, contribution, or tracking.

**Repository Mission**: Enterprise application development framework based on Java with databases, services, UI, security, caching, search, workflow, and integration capabilities.

### Current State

| Category | Count | Action Needed |
|----------|-------|---------------|
| Open Issues | 55 | Triage and prioritize |
| Open PRs | 26 | Review and merge/close |
| Stale Items (3+ years) | ~25 | Close with explanation |
| High-Value PRs | 10 | Recommend merge |

---

## Open Issues Analysis (55 Total)

### P0 - Critical Bugs (Should Fix)

These issues represent runtime failures, crashes, or severe performance problems that affect production systems.

| Issue | Title | Age | Impact | Assignee |
|-------|-------|-----|--------|----------|
| [#651](https://github.com/moqui/moqui-framework/issues/651) | NPE loading Elasticsearch entities at startup | 10mo | Runtime crash, blocks ES users | - |
| [#622](https://github.com/moqui/moqui-framework/issues/622) | 100% CPU for pressure testing database | 2yr | Critical performance issue | - |
| [#601](https://github.com/moqui/moqui-framework/issues/601) | Connection pool issues | 2yr | Production outages possible | - |
| [#590](https://github.com/moqui/moqui-framework/issues/590) | Deadlock of Asset | 2yr | Concurrency bug, data corruption | - |
| [#589](https://github.com/moqui/moqui-framework/issues/589) | Deadlock of Login | 2yr | Auth system deadlock | - |

**Recommended Action**: Create corresponding issues in hunterino/moqui to track fixes. PRs #652 addresses #651.

---

### P1 - Important Bugs (Should Consider)

These are significant bugs that affect functionality but don't cause complete system failures.

| Issue | Title | Age | Impact |
|-------|-------|-----|--------|
| [#668](https://github.com/moqui/moqui-framework/issues/668) | Query parameter disappears from browser address bar | 3mo | UX issue, affects deep linking |
| [#646](https://github.com/moqui/moqui-framework/issues/646) | Incorrect argument name `thruUpdatedStamp` | 14mo | API consistency issue |
| [#641](https://github.com/moqui/moqui-framework/issues/641) | CSV parsing issues with embedded quotes | 16mo | Data import broken |
| [#635](https://github.com/moqui/moqui-framework/issues/635) | Audit logs don't record deletions | 18mo | Compliance/security gap |
| [#615](https://github.com/moqui/moqui-framework/issues/615) | Catalog/Search ordering broken | 2yr | Search functionality |
| [#613](https://github.com/moqui/moqui-framework/issues/613) | Error after order unapproved, inventory import | 2yr | Order workflow |
| [#612](https://github.com/moqui/moqui-framework/issues/612) | Clear Parameters query incorrect results | 2yr | Query correctness |
| [#611](https://github.com/moqui/moqui-framework/issues/611) | BigDecimal unconditional cast issue | 2yr | Type safety bug |
| [#606](https://github.com/moqui/moqui-framework/issues/606) | Entity find ignores non-PK conditions | 2yr | Query correctness |
| [#596](https://github.com/moqui/moqui-framework/issues/596) | Too many 'Potential lock conflict' | 2yr | Logging noise |
| [#592](https://github.com/moqui/moqui-framework/issues/592) | ES sync fail | 2yr | Search sync broken |
| [#591](https://github.com/moqui/moqui-framework/issues/591) | Job lock issues | 2yr | Scheduler reliability |

**Recommended Action**: Evaluate each for reproduction and fix feasibility. PR #642 addresses #641.

---

### P2 - Enhancements (Evaluate for Scope)

Feature requests and improvements that align with the framework's goals.

| Issue | Title | Age | Recommendation | Rationale |
|-------|-------|-----|----------------|-----------|
| [#654](https://github.com/moqui/moqui-framework/issues/654) | Enhancing Dynamic Views | 9mo | **KEEP** | Aligns with framework flexibility goals |
| [#598](https://github.com/moqui/moqui-framework/issues/598) | getLoginKey optimization | 2yr | **KEEP** | Performance improvement |
| [#594](https://github.com/moqui/moqui-framework/issues/594) | Hazelcast Kubernetes support | 2yr | **KEEP** | Cloud-native deployment |
| [#593](https://github.com/moqui/moqui-framework/issues/593) | Batch insert for data import | 2yr | **KEEP** | Performance improvement |
| [#579](https://github.com/moqui/moqui-framework/issues/579) | entity-find-count with having-econditions | 2yr | **KEEP** | Query capability |
| [#524](https://github.com/moqui/moqui-framework/issues/524) | Performance issue with delete operations | 3yr | **KEEP** | Performance fix |
| [#436](https://github.com/moqui/moqui-framework/issues/436) | Before/after ordering for components | 4yr | **KEEP** | Modularity improvement |
| [#407](https://github.com/moqui/moqui-framework/issues/407) | Java API / Annotations alternative to XML | 5yr | **KEEP** | Modernization direction |

---

### P3 - Feature Requests (Lower Priority)

Nice-to-have features that don't directly impact core functionality.

| Issue | Title | Age | Recommendation |
|-------|-------|-----|----------------|
| [#640](https://github.com/moqui/moqui-framework/issues/640) | FreeMarker3 Revival | 16mo | **DEFER** - Major undertaking |
| [#599](https://github.com/moqui/moqui-framework/issues/599) | Custom SQL support | 2yr | **DEFER** - Bypasses entity engine |
| [#597](https://github.com/moqui/moqui-framework/issues/597) | Async CSV download | 2yr | **CONSIDER** |
| [#595](https://github.com/moqui/moqui-framework/issues/595) | Entity XML function improvements | 2yr | **CONSIDER** |

---

### Recommend to Close (Not Aligned / Stale)

These issues should be closed with a polite explanation. They are either support questions, infrastructure issues, component-specific, obsolete, or have been inactive for too long.

#### Support Questions (Not Framework Bugs)

| Issue | Title | Age | Reason |
|-------|-------|-----|--------|
| [#657](https://github.com/moqui/moqui-framework/issues/657) | 404 with Quasar 2 / Vue3 | 7mo | Support/config question |
| [#644](https://github.com/moqui/moqui-framework/issues/644) | Forum login broken | 15mo | Infrastructure issue |
| [#602](https://github.com/moqui/moqui-framework/issues/602) | Docker moqui server https issue | 2yr | Support/config question |
| [#401](https://github.com/moqui/moqui-framework/issues/401) | async-supported class question | 5yr | Support question |
| [#395](https://github.com/moqui/moqui-framework/issues/395) | Error params session design question | 5yr | Design question |
| [#394](https://github.com/moqui/moqui-framework/issues/394) | getDataDocuments question | 5yr | Support question |

#### Component-Specific Issues

| Issue | Title | Age | Reason |
|-------|-------|-----|--------|
| [#580](https://github.com/moqui/moqui-framework/issues/580) | Login.xml component error | 2yr | moqui-org component |
| [#539](https://github.com/moqui/moqui-framework/issues/539) | Root title menu localization | 3yr | Component-specific |
| [#499](https://github.com/moqui/moqui-framework/issues/499) | getMenuData incorrect name | 3yr | Component-specific |
| [#348](https://github.com/moqui/moqui-framework/issues/348) | Example app /vapps features | 6yr | Demo app issue |

#### Infrastructure/Architecture Opinions

| Issue | Title | Age | Reason |
|-------|-------|-----|--------|
| [#570](https://github.com/moqui/moqui-framework/issues/570) | ES startup code should be removed | 3yr | Architecture opinion |
| [#569](https://github.com/moqui/moqui-framework/issues/569) | Docker image shouldn't include ES | 3yr | Docker image opinion |

#### Minor Issues / Edge Cases

| Issue | Title | Age | Reason |
|-------|-------|-----|--------|
| [#587](https://github.com/moqui/moqui-framework/issues/587) | OpenSearch download progress | 2yr | Minor UI polish |
| [#554](https://github.com/moqui/moqui-framework/issues/554) | CSV location suffix requirement | 3yr | Minor annoyance |
| [#398](https://github.com/moqui/moqui-framework/issues/398) | UTF-8 BOM in CSV | 5yr | Minor feature |

#### Stale / Likely Resolved

| Issue | Title | Age | Reason |
|-------|-------|-----|--------|
| [#503](https://github.com/moqui/moqui-framework/issues/503) | Service run as user issue | 3yr | No recent activity |
| [#489](https://github.com/moqui/moqui-framework/issues/489) | Batch update/insert/delete | 4yr | Duplicate of #593 |
| [#455](https://github.com/moqui/moqui-framework/issues/455) | entity-find pagination error | 4yr | Likely stale |
| [#438](https://github.com/moqui/moqui-framework/issues/438) | Localized master-detail find | 4yr | Edge case |
| [#420](https://github.com/moqui/moqui-framework/issues/420) | Remove nulls from Map | 5yr | Likely stale |
| [#416](https://github.com/moqui/moqui-framework/issues/416) | WebSocket for SPA | 4yr | Likely stale |
| [#370](https://github.com/moqui/moqui-framework/issues/370) | dataFeed not always executed | 6yr | Likely stale |

#### Database-Specific / Obsolete

| Issue | Title | Age | Reason |
|-------|-------|-----|--------|
| [#327](https://github.com/moqui/moqui-framework/issues/327) | Oracle errors | 6yr | DB-specific, stale |
| [#321](https://github.com/moqui/moqui-framework/issues/321) | Oracle cursor limit | 6yr | DB-specific, stale |
| [#312](https://github.com/moqui/moqui-framework/issues/312) | DB migration 1.6.1 to 2.1.0 | 7yr | Obsolete version |
| [#309](https://github.com/moqui/moqui-framework/issues/309) | Migration 1.6 to 2.1 sqlFind | 7yr | Obsolete version |

---

## Open Pull Requests Analysis (26 Total)

### Recommend to Merge (High Value)

These PRs provide clear value with bug fixes or important improvements.

| PR | Title | Author | Rationale |
|----|-------|--------|-----------|
| [#673](https://github.com/moqui/moqui-framework/pull/673) | Add unit test convenience methods | @pythys | Testing infrastructure improvement |
| [#661](https://github.com/moqui/moqui-framework/pull/661) | Fix OpenSearch macOS startup | @hellozhangwei | Fixes real platform issue |
| [#660](https://github.com/moqui/moqui-framework/pull/660) | Remove RestClient 30s idle timeout | @puru-khedre | Fixes real limitation |
| [#652](https://github.com/moqui/moqui-framework/pull/652) | Move elastic facade init before postFacadeInit | @puru-khedre | Fixes #651 (P0 bug) |
| [#648](https://github.com/moqui/moqui-framework/pull/648) | try-with-resources for JDBC | @dixitdeepak | Code quality, prevents resource leaks |
| [#642](https://github.com/moqui/moqui-framework/pull/642) | CSV escape character support | @puru-khedre | Fixes #641 |
| [#631](https://github.com/moqui/moqui-framework/pull/631) | Fix message queue clearance | @dixitdeepak | Bug fix |
| [#627](https://github.com/moqui/moqui-framework/pull/627) | Entity auto check status fix | @dixitdeepak | Bug fix |
| [#584](https://github.com/moqui/moqui-framework/pull/584) | Add check-empty-type to load | @eigood | Feature improvement |
| [#583](https://github.com/moqui/moqui-framework/pull/583) | Improve service-special error handling | @eigood | Better error messages |

---

### Review Needed (Evaluate Carefully)

These PRs need careful review for scope, security, or breaking changes.

| PR | Title | Author | Review Notes |
|----|-------|--------|--------------|
| [#670](https://github.com/moqui/moqui-framework/pull/670) | Add moqui-minio component | @heguangyong | **Scope**: New component - should this be in core? |
| [#665](https://github.com/moqui/moqui-framework/pull/665) | Documentation + Romanian currency | @grozadanut | **Quality**: Review content accuracy |
| [#663](https://github.com/moqui/moqui-framework/pull/663) | createdStamp support | @dixitdeepak | **Breaking**: Schema change impact? |
| [#655](https://github.com/moqui/moqui-framework/pull/655) | Dynamic Views enhancement | @Shinde-nutan | **Scope**: Matches issue #654, needs review |
| [#653](https://github.com/moqui/moqui-framework/pull/653) | Visit entity relationship fix | @dixitdeepak | **Breaking**: Schema/relationship change |
| [#638](https://github.com/moqui/moqui-framework/pull/638) | SSO token login | @jenshp | **Security**: Needs security review |
| [#637](https://github.com/moqui/moqui-framework/pull/637) | REST path tracking | @jenshp | **Performance**: Impact assessment needed |
| [#634](https://github.com/moqui/moqui-framework/pull/634) | Email reattempt | @jenshp | **Design**: Review retry approach |
| [#633](https://github.com/moqui/moqui-framework/pull/633) | Job run lock expiry | @jenshp | **Design**: Review lock handling |
| [#621](https://github.com/moqui/moqui-framework/pull/621) | Container macro condition | @acetousk | **Scope**: Review necessity |

---

### Likely Stale (Close or Request Update)

These PRs have been open for extended periods without activity and may no longer apply cleanly.

| PR | Title | Author | Age | Action |
|----|-------|--------|-----|--------|
| [#532](https://github.com/moqui/moqui-framework/pull/532) | Fix savedFinds pathWithParams | @chunlinyao | 3yr | Request rebase or close |
| [#469](https://github.com/moqui/moqui-framework/pull/469) | Vietnam provinces data | @donhuvy | 4yr | Request update or close |
| [#440](https://github.com/moqui/moqui-framework/pull/440) | try/catch/finally in XmlActions | @Destrings2 | 4yr | Request update or close |
| [#437](https://github.com/moqui/moqui-framework/pull/437) | Before/after ordering | @eigood | 4yr | Related to #436, request update |
| [#356](https://github.com/moqui/moqui-framework/pull/356) | Force en_US locale for XML/CSV | @jenshp | 6yr | Request update or close |
| [#305](https://github.com/moqui/moqui-framework/pull/305) | Configurable cookie names | @shendepu | 7yr | Close - too stale |

---

## Recommended Action Plan

### Phase 1: Triage (Week 1)

**Goal**: Clean up backlog and establish clear priorities

1. **Close stale issues** (25+ items)
   - Use standardized message template (see Appendix A)
   - Issues older than 3 years with no recent activity
   - Support questions and component-specific issues

2. **Close stale PRs** (6 items)
   - Request rebase/update with 2-week deadline
   - Close if no response

3. **Label remaining issues**
   - Apply priority labels (P0-P4)
   - Apply epic labels where applicable

### Phase 2: Quick Wins (Week 2-3)

**Goal**: Merge high-value PRs and address critical bugs

1. **Merge recommended PRs** (10 items)
   - #652, #648, #661, #660, #642
   - #631, #627, #584, #583, #673

2. **Create tracking issues** in hunterino/moqui for:
   - P0 deadlock issues (#589, #590)
   - Connection pool issues (#601)
   - ES sync problems (#592)

### Phase 3: Bug Fixes (Week 4-6)

**Goal**: Address P0 and P1 bugs

1. **Deadlock Resolution**
   - Investigate #589 (Login deadlock)
   - Investigate #590 (Asset deadlock)
   - Root cause analysis and fixes

2. **Performance Issues**
   - Address #622 (100% CPU)
   - Review #601 (Connection pool)

3. **Search/ES Issues**
   - Ensure #652 merged (fixes #651)
   - Address #592 (ES sync)

### Phase 4: Enhancements (Week 7+)

**Goal**: Implement valuable enhancements

1. **Dynamic Views** (#654, #655)
2. **Batch Operations** (#593)
3. **Component Ordering** (#436, #437)

---

## Appendix A: Issue Closure Templates

### Stale Issue Template

```markdown
Thank you for reporting this issue. After reviewing our backlog, we're closing issues that have been inactive for an extended period.

If this issue is still relevant:
1. Please open a new issue with updated reproduction steps
2. Reference this issue number for context
3. Include your Moqui Framework version

We appreciate your understanding as we work to maintain a focused and actionable issue tracker.
```

### Support Question Template

```markdown
Thank you for your question. This appears to be a support/configuration question rather than a framework bug.

For support questions, please use:
- [Moqui Forum](https://forum.moqui.org/)
- [Moqui Slack](https://moqui.slack.com/)

If you believe this is actually a bug, please open a new issue with:
1. Moqui Framework version
2. Steps to reproduce
3. Expected vs actual behavior
```

### Duplicate Issue Template

```markdown
This issue appears to be a duplicate of #XXX. We're closing this to consolidate discussion.

Please follow #XXX for updates. If you have additional information, please add it to that issue.
```

---

## Appendix B: Priority Definitions

| Priority | Label | Description | SLA |
|----------|-------|-------------|-----|
| P0 | `priority:P0` | Critical - System crash, data loss, security vulnerability | Fix within 1 week |
| P1 | `priority:P1` | High - Major functionality broken, significant impact | Fix within 1 month |
| P2 | `priority:P2` | Medium - Important but workaround exists | Fix within 1 quarter |
| P3 | `priority:P3` | Low - Minor issues, enhancements | Best effort |
| P4 | `priority:P4` | Nice to have - Future consideration | No commitment |

---

## Appendix C: Epic Definitions

| Epic | Label | Description |
|------|-------|-------------|
| Security | `epic:security` | Security vulnerabilities and hardening |
| Performance | `epic:performance` | Performance optimizations |
| Entity Engine | `epic:entity` | Database/entity layer issues |
| Service Engine | `epic:service` | Service framework issues |
| Screen/UI | `epic:screen` | Screen rendering and UI |
| Search | `epic:search` | Elasticsearch/OpenSearch integration |
| Docker/K8s | `epic:docker` | Containerization and orchestration |
| Testing | `epic:testing` | Test infrastructure and coverage |

---

## Change Log

| Date | Author | Changes |
|------|--------|---------|
| 2025-12-07 | Claude Code | Initial analysis and prioritization |
