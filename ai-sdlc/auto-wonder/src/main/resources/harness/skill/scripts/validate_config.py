#!/usr/bin/env python3
"""Validate portable harness data without contacting AutoWonder or running Skills."""
import argparse
import json
import re
from pathlib import Path, PurePosixPath

TOKEN = re.compile(r"\{\{[^{}\n]+\}\}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


def unique(items, field, context):
    values = [item.get(field) for item in items]
    require(all(isinstance(v, str) and v.strip() for v in values), f"{context}: missing {field}")
    require(len(values) == len(set(values)), f"{context}: duplicate {field}")
    return set(values)


def validate(data, instantiated=False):
    require(isinstance(data, list) and data, "root must be a non-empty template array")
    require(all(isinstance(t, dict) for t in data), "each template must be an object")
    template_names, global_roles, global_skills = set(), set(), {}
    for t in data:
        require(t.get("schemaVersion") == 2, "unsupported schemaVersion (expected 2)")
        require(set(t) == {"schemaVersion", "template", "squad", "variables", "skills", "agents"}, "unexpected or missing template fields")
        meta, squad = t["template"], t["squad"]
        require(isinstance(meta, dict) and isinstance(squad, dict), "template/squad must be objects")
        for item in (meta, squad):
            require(isinstance(item.get("name"), str) and item["name"].strip(), "missing name")
            require(isinstance(item.get("description"), str), "missing description")
        name = meta["name"]
        require(name not in template_names, "duplicate template name")
        template_names.add(name)
        agents, skills, variables = t["agents"], t["skills"], t["variables"]
        require(isinstance(agents, list) and agents and all(isinstance(a, dict) for a in agents), f"{name}: agents must be non-empty objects")
        require(isinstance(skills, list) and all(isinstance(s, dict) for s in skills), f"{name}: skills must be objects")
        require(isinstance(variables, list) and all(isinstance(v, dict) for v in variables), f"{name}: variables must be objects")
        require(type(meta.get("squadSize")) is int and meta["squadSize"] == len(agents), f"{name}: squadSize mismatch")
        unique(agents, "name", name)
        roles = unique(agents, "roleCode", name)
        require(not roles & global_roles, f"{name}: roleCode conflicts with another template")
        global_roles |= roles
        keys = unique(skills, "key", name)
        unique(skills, "name", name)
        skill_names = {s["key"]: s["name"] for s in skills}
        for s in skills:
            require(set(s) == {"key", "name", "type", "description", "files"}, f"{s['name']}: unexpected or missing Skill fields")
            require(s["type"] == "SKILL" and isinstance(s["description"], str), "unsupported capability type/description")
            files = s["files"]
            require(isinstance(files, dict) and "SKILL.md" in files, f"{s['name']}: missing root SKILL.md")
            for path, content in files.items():
                p = PurePosixPath(path)
                require(path and not p.is_absolute() and ".." not in p.parts and "\\" not in path and ":" not in path and str(p) == path, f"unsafe Skill path: {path}")
                require(isinstance(content, str) and "\x00" not in content, f"{path}: expected UTF-8 text")
                content.encode("utf-8")
                if path.endswith(".md"):
                    for target in re.findall(r"\[[^\]]*\]\(([^\s)]+)\)", content):
                        if "://" in target or target.startswith(("#", "mailto:")):
                            continue
                        relative = target.split("#", 1)[0]
                        require(str(p.parent / relative) in files, f"{s['name']}/{path}: missing relative file {relative}")
            front = re.match(r"\A---\n(.*?)\n---(?:\n|$)", files["SKILL.md"], re.S)
            require(front is not None, f"{s['name']}: missing frontmatter")
            declared = re.search(r"^name:\s*(.+)$", front[1], re.M)
            require(declared is not None and declared[1].strip().strip('\"\'') == s["name"], f"{s['name']}: frontmatter name mismatch")
            require(re.search(r"^description:\s*\S", front[1], re.M), f"{s['name']}: missing description")
            require(s["name"] not in global_skills or global_skills[s["name"]] == files, f"{s['name']}: conflicting cross-template contents")
            global_skills[s["name"]] = files
        for a in agents:
            require(set(a) == {"name", "roleCode", "roleName", "businessBackground", "responsibilities", "evolutionMode", "skillRefs", "sdlc"}, f"{a['name']}: unexpected or missing agent fields")
            require(all(isinstance(a[k], str) for k in ("roleName", "businessBackground", "responsibilities", "evolutionMode")), "agent text fields must be strings")
            refs = a["skillRefs"]
            require(isinstance(refs, list) and all(isinstance(r, str) for r in refs), "skillRefs must be string keys")
            require(len(refs) == len(set(refs)) and set(refs) <= keys, f"{a['name']}: duplicate or unresolved skillRefs")
            bound_names = {skill_names[r] for r in refs}
            for text in strings([a["responsibilities"], a["businessBackground"], a["sdlc"]]):
                for skill_name in skill_names.values():
                    require(f"`{skill_name}`" not in text or skill_name in bound_names, f"{a['name']}: explicit Skill not bound: {skill_name}")
                for role in re.findall(r"\bAW_AUTO_[A-Z_]+\b", text):
                    require(role in roles, f"unresolved role: {role}")
            flow = a["sdlc"]
            if flow is None:
                continue
            require(isinstance(flow, dict) and isinstance(flow.get("name"), str) and flow["name"], "invalid SDLC")
            require(set(flow) == {"name", "description", "workType", "status", "isDefault", "steps"}, "unexpected or missing SDLC fields")
            require(flow.get("isDefault") is False, "global default SDLC is unsupported")
            require(flow.get("status") in ("DRAFT", "ENABLED", "DISABLED"), "invalid SDLC status")
            steps = flow.get("steps")
            require(isinstance(steps, list) and steps and all(isinstance(s, dict) for s in steps), "SDLC requires steps")
            unique(steps, "code", flow["name"])
            orders = [s.get("order") for s in steps]
            require(all(type(n) is int and n > 0 for n in orders) and orders == sorted(set(orders)), "step orders must strictly increase")
            for step in steps:
                require(set(step) == {"order", "name", "kind", "instruction", "checklist", "gatePolicy", "required", "timeoutSeconds", "retryBudget", "code", "handlerType", "handlerRoleRef", "statusOnEnterCode", "onSuccess", "onFail"}, "unexpected or missing step fields")
                require(type(step["required"]) is bool, "required must be boolean")
                require(all(step[k] is None or type(step[k]) is int for k in ("timeoutSeconds", "retryBudget")), "step limits must be integer/null")
                require(isinstance(step.get("instruction"), str) and step["instruction"].strip(), "empty step instruction")
                require(step.get("checklist") is None or isinstance(step["checklist"], list), "checklist must be array/null")
                require(step.get("gatePolicy") is None or isinstance(step["gatePolicy"], dict), "gatePolicy must be object/null")
                require(step.get("handlerType") != "AGENT" or step.get("handlerRoleRef") in roles, "unresolved handler role")
        declared = unique(variables, "token", name)
        require(all(TOKEN.fullmatch(v) for v in declared), "invalid variable token")
        require(all(isinstance(v.get(k), str) and v[k] for v in variables for k in ("label", "requiredWhen")), "variable guidance missing")
        used = {v for text in strings({k: v for k, v in t.items() if k != "variables"}) for v in TOKEN.findall(text)}
        require(not used if instantiated else used == declared, "unresolved or undeclared variables" if instantiated else "variable declarations do not match content")
    return len(data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", nargs="?", type=Path, default=Path(__file__).resolve().parents[1] / "harness-config.json")
    parser.add_argument("--instantiated", action="store_true")
    args = parser.parse_args()
    try:
        count = validate(json.loads(args.config.read_text(encoding="utf-8")), args.instantiated)
    except (ValueError, TypeError, KeyError, OSError, UnicodeError) as exc:
        parser.exit(1, f"Invalid harness config: {exc}\n")
    print(f"Valid harness config: {count} template(s)")
