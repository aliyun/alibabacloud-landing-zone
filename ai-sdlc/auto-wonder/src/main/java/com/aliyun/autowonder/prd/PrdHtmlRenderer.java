package com.aliyun.autowonder.prd;

import java.util.List;

/**
 * Renders the HTML PRD from {@link PrdDesignData}.
 *
 * <p>Rendering is deterministic and server-owned: the HTML is derived from the same versioned
 * design data that produces {@code requirements.json}, so the two artifacts cannot drift. Every
 * text value is escaped, and interactive prototypes are embedded in a nested sandboxed iframe
 * without same-origin access, so prototype markup can neither reach the PRD document nor the host
 * page. The document carries a CSP that forbids all external network access.
 */
public final class PrdHtmlRenderer {

    /** Bump when the rendered document structure changes; stored on the version row. */
    public static final String RENDERER_VERSION = "prd-html-renderer-v1";

    private static final String CSP = "default-src 'none'; img-src data: blob:; "
            + "style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'";

    private final java.util.Locale locale;

    private PrdHtmlRenderer(String language) {
        this.locale = "en-US".equals(language) ? java.util.Locale.US : java.util.Locale.SIMPLIFIED_CHINESE;
    }

    private String label(String key) {
        return com.aliyun.autowonder.common.i18n.SystemMessages.render(
                "prd.html." + key, new Object[0], key, locale);
    }

    public static String render(PrdDesignData data) {
        return new PrdHtmlRenderer(data.getLanguage()).renderDocument(data);
    }

    private String renderDocument(PrdDesignData data) {
        StringBuilder html = new StringBuilder(16 * 1024);
        html.append("<!DOCTYPE html>\n<html lang=\"" + (java.util.Locale.US.equals(locale) ? "en-US" : "zh-CN") + "\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(CSP).append("\">\n")
            .append("<meta name=\"referrer\" content=\"no-referrer\">\n")
            // Never let a prototype link navigate the top-level host window.
            .append("<base target=\"_self\">\n")
            .append("<title>").append(esc(data.getTitle())).append("</title>\n")
            .append("<style>").append(styles()).append("</style>\n")
            .append("</head>\n<body>\n");

        html.append("<main>\n");
        html.append("<h1>").append(esc(data.getTitle())).append("</h1>\n");

        section(html, label("background"), data.getBackground());
        section(html, label("goals"), data.getGoals());
        listSection(html, label("roles"), data.getUserRoles());

        html.append("<section id=\"scope\">\n<h2>" + label("scope") + "</h2>\n");
        html.append("<h3>" + label("in_scope") + "</h3>\n");
        bulletList(html, data.getInScope());
        html.append("<h3>" + label("non_goals") + "</h3>\n");
        bulletList(html, data.getNonGoals());
        html.append("</section>\n");

        if (notBlank(data.getTargetProjectAdaptation())) {
            section(html, label("adaptation"), data.getTargetProjectAdaptation());
        }

        if (!isEmpty(data.getFeatureEntries())) {
            html.append("<section id=\"entries\">\n<h2>" + label("entries") + "</h2>\n")
                .append("<table><thead><tr><th>" + label("location") + "</th><th>" + label("capability") + "</th></tr></thead><tbody>\n");
            for (PrdDesignData.FeatureEntry entry : data.getFeatureEntries()) {
                html.append("<tr><td>").append(esc(entry.getLocation()))
                    .append("</td><td>").append(esc(entry.getCapability())).append("</td></tr>\n");
            }
            html.append("</tbody></table>\n</section>\n");
        }

        if (!isEmpty(data.getUserFlows())) {
            html.append("<section id=\"flows\">\n<h2>" + label("flows") + "</h2>\n<ol>\n");
            for (PrdDesignData.FlowStep step : data.getUserFlows()) {
                html.append("<li>");
                if (notBlank(step.getActor())) {
                    html.append("<strong>").append(esc(step.getActor())).append("</strong>：");
                }
                html.append(esc(step.getAction())).append("</li>\n");
            }
            html.append("</ol>\n</section>\n");
        }

        if (!isEmpty(data.getPages())) {
            html.append("<section id=\"pages\">\n<h2>" + label("pages") + "</h2>\n");
            for (PrdDesignData.PageDesign page : data.getPages()) {
                html.append("<article class=\"page\" id=\"page-").append(esc(page.getPageKey())).append("\">\n")
                    .append("<h3>").append(esc(page.getName())).append("</h3>\n");
                if (notBlank(page.getRoute())) {
                    html.append("<p class=\"route\">" + label("route") + "<code>").append(esc(page.getRoute()))
                        .append("</code></p>\n");
                }
                if (notBlank(page.getLayout())) {
                    html.append("<p>").append(esc(page.getLayout())).append("</p>\n");
                }
                if (!isEmpty(page.getInteractions())) {
                    html.append("<h4>" + label("interactions") + "</h4>\n");
                    bulletList(html, page.getInteractions());
                }
                html.append("<h4>" + label("states") + "</h4>\n<table class=\"states\"><tbody>\n");
                stateRow(html, label("normal"), page.getNormalState());
                stateRow(html, label("loading"), page.getLoadingState());
                stateRow(html, label("empty"), page.getEmptyState());
                stateRow(html, label("error"), page.getErrorState());
                stateRow(html, label("forbidden"), page.getForbiddenState());
                html.append("</tbody></table>\n");
                appendPrototype(html, page);
                html.append("</article>\n");
            }
            html.append("</section>\n");
        }

        if (!isEmpty(data.getDataFields())) {
            html.append("<section id=\"fields\">\n<h2>" + label("fields") + "</h2>\n")
                .append("<table><thead><tr><th>" + label("field") + "</th><th>" + label("type") + "</th><th>" + label("required") + "</th>")
                .append("<th>" + label("validation") + "</th><th>" + label("description") + "</th></tr></thead><tbody>\n");
            for (PrdDesignData.DataField field : data.getDataFields()) {
                html.append("<tr><td><code>").append(esc(field.getName())).append("</code></td><td>")
                    .append(esc(field.getType())).append("</td><td>")
                    .append(field.isRequired() ? label("yes") : label("no")).append("</td><td>")
                    .append(esc(field.getValidation())).append("</td><td>")
                    .append(esc(field.getDescription())).append("</td></tr>\n");
            }
            html.append("</tbody></table>\n</section>\n");
        }

        if (!isEmpty(data.getInterfaces())) {
            html.append("<section id=\"interfaces\">\n<h2>" + label("interfaces") + "</h2>\n<p class=\"note\">")
                .append(label("api_note") + "</p>\n")
                .append("<table><thead><tr><th>" + label("api") + "</th><th>" + label("method") + "</th><th>" + label("path") + "</th>")
                .append("<th>" + label("states") + "</th><th>" + label("description") + "</th></tr></thead><tbody>\n");
            for (PrdDesignData.InterfaceDependency api : data.getInterfaces()) {
                html.append("<tr><td>").append(esc(api.getName())).append("</td><td><code>")
                    .append(esc(api.getMethod())).append("</code></td><td><code>")
                    .append(esc(api.getPath())).append("</code></td><td>")
                    .append(interfaceBadge(api.getStatus())).append("</td><td>")
                    .append(esc(api.getDescription())).append("</td></tr>\n");
            }
            html.append("</tbody></table>\n</section>\n");
        }

        html.append("<section id=\"requirements\">\n<h2>" + label("requirements") + "</h2>\n");
        if (isEmpty(data.getRequirements())) {
            html.append("<p class=\"empty\">" + label("no_requirements") + "</p>\n");
        }
        for (PrdDesignData.Requirement requirement : data.getRequirements()) {
            html.append("<article class=\"requirement\" id=\"req-").append(esc(requirement.getKey())).append("\">\n")
                .append("<h3><span class=\"key\">").append(esc(requirement.getKey())).append("</span> ")
                .append(esc(requirement.getTitle()));
            if (notBlank(requirement.getPriority())) {
                html.append(" <span class=\"badge\">").append(esc(requirement.getPriority())).append("</span>");
            }
            if (requirement.isExcluded()) {
                html.append(" <span class=\"badge excluded\">" + label("excluded") + "</span>");
            }
            html.append("</h3>\n");
            if (notBlank(requirement.getDescription())) {
                html.append("<p>").append(esc(requirement.getDescription())).append("</p>\n");
            }
            if (!isEmpty(requirement.getAcceptanceCriteria())) {
                html.append("<h4>" + label("criteria") + "</h4>\n<ol class=\"criteria\">\n");
                for (String criterion : requirement.getAcceptanceCriteria()) {
                    html.append("<li>").append(esc(criterion)).append("</li>\n");
                }
                html.append("</ol>\n");
            }
            html.append("</article>\n");
        }
        html.append("</section>\n");

        if (!isEmpty(data.getOpenQuestions())) {
            html.append("<section id=\"questions\">\n<h2>" + label("questions") + "</h2>\n<ul class=\"questions\">\n");
            for (PrdDesignData.OpenQuestion question : data.getOpenQuestions()) {
                html.append("<li class=\"").append(question.isResolved() ? "resolved" : "open")
                    .append(question.isBlocking() ? " blocking" : "").append("\">")
                    .append("<span class=\"key\">").append(esc(question.getKey())).append("</span> ")
                    .append(esc(question.getQuestion()));
                if (question.isBlocking() && !question.isResolved()) {
                    html.append(" <span class=\"badge blocking\">" + label("blocking") + "</span>");
                }
                if (question.isResolved()) {
                    html.append(" <span class=\"badge resolved\">" + label("resolved") + "</span>");
                    if (notBlank(question.getResolution())) {
                        html.append("<div class=\"resolution\">").append(esc(question.getResolution()))
                            .append("</div>");
                    }
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n</section>\n");
        }

        listSection(html, label("risks"), data.getRisks());

        if (notBlank(data.getChangeLog())) {
            section(html, label("changes"), data.getChangeLog());
        }

        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    /**
     * Embed the prototype markup in a nested sandboxed iframe. Without allow-same-origin the
     * prototype cannot read the PRD document or host state; allow-scripts keeps its controlled
     * interactions working, and the parent CSP blocks any external fetch.
     */
    private void appendPrototype(StringBuilder html, PrdDesignData.PageDesign page) {
        if (!notBlank(page.getMockHtml())) {
            return;
        }
        String srcdoc = esc(page.getMockHtml());
        html.append("<h4>" + label("prototype_mock") + "</h4>\n")
            .append("<iframe class=\"prototype\" sandbox=\"allow-scripts\" srcdoc=\"").append(srcdoc)
            .append("\" title=\"").append(esc(page.getName())).append(" " + label("prototype") + "\" loading=\"lazy\">")
            .append("</iframe>\n");
    }

    private String interfaceBadge(String status) {
        if (status == null) {
            return "<span class=\"badge unknown\">" + label("unconfirmed") + "</span>";
        }
        return switch (status) {
            case "EXISTING" -> "<span class=\"badge existing\">" + label("existing") + "</span>";
            case "PLANNED" -> "<span class=\"badge planned\">" + label("planned") + "</span>";
            default -> "<span class=\"badge unknown\">" + label("unconfirmed") + "</span>";
        };
    }

    private void section(StringBuilder html, String heading, String body) {
        if (!notBlank(body)) {
            return;
        }
        html.append("<section>\n<h2>").append(esc(heading)).append("</h2>\n<p>")
            .append(esc(body)).append("</p>\n</section>\n");
    }

    private void listSection(StringBuilder html, String heading, List<String> items) {
        if (isEmpty(items)) {
            return;
        }
        html.append("<section>\n<h2>").append(esc(heading)).append("</h2>\n");
        bulletList(html, items);
        html.append("</section>\n");
    }

    private void bulletList(StringBuilder html, List<String> items) {
        if (isEmpty(items)) {
            html.append("<p class=\"empty\">" + label("not_filled") + "</p>\n");
            return;
        }
        html.append("<ul>\n");
        for (String item : items) {
            html.append("<li>").append(esc(item)).append("</li>\n");
        }
        html.append("</ul>\n");
    }

    private void stateRow(StringBuilder html, String label, String value) {
        html.append("<tr><th scope=\"row\">").append(esc(label)).append("</th><td>")
            .append(notBlank(value) ? esc(value) : "<span class=\"empty\">" + label("undefined") + "</span>")
            .append("</td></tr>\n");
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Escape for both element text and double-quoted attribute values. */
    static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String styles() {
        return """
                :root{color-scheme:light dark}
                body{margin:0;padding:24px;font:14px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",\
                "PingFang SC","Microsoft YaHei",sans-serif;color:#1f2329;background:#fff}
                main{max-width:960px;margin:0 auto}
                h1{font-size:24px;margin:0 0 20px}
                h2{font-size:18px;margin:28px 0 10px;padding-bottom:6px;border-bottom:1px solid #e5e6eb}
                h3{font-size:15px;margin:16px 0 6px}
                h4{font-size:13px;margin:12px 0 4px;color:#4e5969}
                table{border-collapse:collapse;width:100%;margin:8px 0}
                th,td{border:1px solid #e5e6eb;padding:6px 10px;text-align:left;vertical-align:top}
                th{background:#f7f8fa;font-weight:600}
                code{background:#f2f3f5;padding:1px 4px;border-radius:3px;font-size:12px}
                .page,.requirement{border:1px solid #e5e6eb;border-radius:6px;padding:12px 16px;margin:12px 0}
                .key{font-family:ui-monospace,Menlo,monospace;color:#165dff;font-weight:600}
                .badge{display:inline-block;padding:0 6px;border:1px solid #e5e6eb;border-radius:10px;\
                font-size:11px;color:#4e5969}
                .badge.existing{border-color:#b7eb8f;color:#237804}
                .badge.planned{border-color:#ffd591;color:#ad4e00}
                .badge.unknown{border-color:#e5e6eb;color:#4e5969}
                .badge.blocking{border-color:#ffa39e;color:#a8071a}
                .badge.resolved{border-color:#b7eb8f;color:#237804}
                .badge.excluded{border-color:#d9d9d9;color:#8c8c8c}
                .route,.note{color:#4e5969;font-size:12px}
                .empty{color:#86909c;font-style:italic}
                .prototype{width:100%;height:420px;border:1px solid #e5e6eb;border-radius:4px;background:#fff}
                ol.criteria,ul.questions{margin:6px 0;padding-left:22px}
                li.blocking{color:#a8071a}
                .resolution{color:#4e5969;font-size:12px;margin-top:2px}
                """;
    }
}
