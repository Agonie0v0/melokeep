from pathlib import Path
import re
from xml.etree import ElementTree


root = Path(__file__).resolve().parents[2]
resource_dirs = (
    root / "app/src/main/res",
)


def names(resources, folder):
    return {
        item.attrib["name"]: item
        for item in ElementTree.parse(resources / folder / "strings.xml").getroot()
        if item.attrib.get("translatable") != "false"
    }


for resources in resource_dirs:
    source = names(resources, "values")
    chinese = names(resources, "values-zh-rCN")
    missing = sorted(source.keys() - chinese.keys())
    if missing:
        raise SystemExit(f"Missing Simplified Chinese resources in {resources}: " + ", ".join(missing))
    for name, item in source.items():
        translated = chinese[name]
        if item.tag != translated.tag:
            raise SystemExit(f"Resource type mismatch for {name}")
        def parameters(element):
            return set(re.findall(r"%(?:\d+\$)?[dsf]", "".join(element.itertext())))
        if parameters(item) != parameters(translated):
            raise SystemExit(f"Format parameter mismatch for {name}")
print("Simplified Chinese covers all translatable resources.")
