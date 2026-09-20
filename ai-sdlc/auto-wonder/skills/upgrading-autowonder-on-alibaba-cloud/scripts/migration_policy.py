"""Conservative SQL risk classification; never treats DROP INDEX as data loss."""
import re


def classify_sql(sql):
    # Preserve backtick identifiers, but exclude comments and literal values.
    clean = re.sub(r"--[^\n]*|/\*(?!\!)[\s\S]*?\*/|\#[^\n]*|'(?:''|\\.|[^'\\])*'|\"(?:\"\"|\\.|[^\"\\])*\"", " ", sql)
    operations = sorted(set(re.findall(r'\b(ALTER|DROP|TRUNCATE|RENAME|CREATE|UPDATE|DELETE|INSERT)\b', clean.upper())))
    without_index_drop = re.sub(r'\bDROP\s+(?:INDEX|KEY)\s+(?:`[^`]+`|[\w]+)', ' ', clean, flags=re.I)
    destructive = bool(re.search(r'\b(DROP|TRUNCATE|DELETE)\b', without_index_drop, re.I))
    index_change = bool(re.search(r'\bDROP\s+(?:INDEX|KEY)\b', clean, re.I))
    return dict(riskOperations=operations, destructive=destructive,
                maintenanceRequired=destructive or index_change or bool(re.search(r'\bRENAME\b', clean, re.I)),
                indexChange=index_change)
