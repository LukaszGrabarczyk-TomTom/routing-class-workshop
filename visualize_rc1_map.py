#!/usr/bin/env python3
"""Visualize RC1 connected components on an interactive Leaflet map."""

import pandas as pd
import folium
from collections import defaultdict

# --- Config ---
INPUT_CSV = "edges_geometry.csv"
OUTPUT_HTML = "rc1_components_map.html"
TARGET_RC = 1

# Qualitative palette for components (large enough for many components)
COMPONENT_COLORS = [
    "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
    "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990",
    "#dcbeff", "#9A6324", "#800000", "#aaffc3", "#808000",
    "#000075", "#a9a9a9", "#e6beff", "#ffe119", "#ff6961",
]

# --- 1. Load and filter to RC1 ---
print("Loading CSV...")
df = pd.read_csv(INPUT_CSV)
rc1 = df[df["computed_rc"] == TARGET_RC].copy()
print(f"RC1 rows: {len(rc1)}")

# --- 2. Reconstruct polylines per edge ---
print("Building edge polylines...")
edges = {}
for edge_id, group in rc1.groupby("edge_id"):
    group = group.sort_values("point_idx")
    coords = list(zip(group["lat"].values, group["lon"].values))  # Folium uses (lat, lon)
    edges[edge_id] = {
        "coords": coords,
        "highway": group["highway"].iloc[0],
        "from_node": int(group["from_node"].iloc[0]),
        "to_node": int(group["to_node"].iloc[0]),
    }
print(f"RC1 edges: {len(edges)}")

# --- 3. Union-Find for connected components (using actual node IDs) ---
parent = {}

def find(x):
    while parent[x] != x:
        parent[x] = parent[parent[x]]
        x = parent[x]
    return x

def union(a, b):
    ra, rb = find(a), find(b)
    if ra != rb:
        parent[ra] = rb

for eid, e in edges.items():
    s, t = e["from_node"], e["to_node"]
    parent.setdefault(s, s)
    parent.setdefault(t, t)
    union(s, t)

# Assign component ID per edge
comp_map = {}  # root -> component_id
comp_id_counter = 0
edge_component = {}
for eid, e in edges.items():
    root = find(e["from_node"])
    if root not in comp_map:
        comp_map[root] = comp_id_counter
        comp_id_counter += 1
    edge_component[eid] = comp_map[root]

# Count edges per component
comp_sizes = defaultdict(int)
for cid in edge_component.values():
    comp_sizes[cid] += 1

# Sort components by size descending for color assignment
size_rank = sorted(comp_sizes.keys(), key=lambda c: comp_sizes[c], reverse=True)
rank_map = {cid: rank for rank, cid in enumerate(size_rank)}

print(f"Connected components: {len(comp_sizes)}")
for i, cid in enumerate(size_rank[:10]):
    print(f"  Component {cid}: {comp_sizes[cid]} edges")
if len(size_rank) > 10:
    print(f"  ... and {len(size_rank) - 10} more small components")

# --- 4. Build Folium map ---
print("Building map...")
center_lat = rc1["lat"].mean()
center_lon = rc1["lon"].mean()

m = folium.Map(location=[center_lat, center_lon], zoom_start=10, tiles="OpenStreetMap")

for eid, e in edges.items():
    cid = edge_component[eid]
    rank = rank_map[cid]
    color = COMPONENT_COLORS[rank % len(COMPONENT_COLORS)]
    size = comp_sizes[cid]

    popup_html = (
        f"<b>Edge:</b> {eid}<br>"
        f"<b>Highway:</b> {e['highway']}<br>"
        f"<b>Component:</b> {cid} ({size} edges)"
    )

    folium.PolyLine(
        locations=e["coords"],
        color=color,
        weight=4,
        opacity=0.85,
        popup=folium.Popup(popup_html, max_width=250),
        tooltip=f"Comp {cid} ({size} edges)",
    ).add_to(m)

# --- 5. Add legend ---
legend_html = """
<div style="position: fixed; bottom: 30px; left: 30px; z-index: 1000;
     background: white; padding: 12px 16px; border-radius: 8px;
     box-shadow: 0 2px 6px rgba(0,0,0,0.3); font-family: sans-serif; font-size: 13px;">
<b>RC1 Components</b><br>
"""
for i, cid in enumerate(size_rank[:10]):
    color = COMPONENT_COLORS[i % len(COMPONENT_COLORS)]
    legend_html += (
        f'<span style="background:{color};width:14px;height:14px;'
        f'display:inline-block;margin-right:6px;border-radius:2px;"></span>'
        f'Comp {cid} — {comp_sizes[cid]} edges<br>'
    )
if len(size_rank) > 10:
    legend_html += f"<i>+{len(size_rank) - 10} more</i><br>"
legend_html += f"<br><b>Total: {len(edges)} edges, {len(comp_sizes)} components</b></div>"

m.get_root().html.add_child(folium.Element(legend_html))

# --- 6. Save ---
m.save(OUTPUT_HTML)
print(f"Saved: {OUTPUT_HTML}")
print("Open in browser to explore.")
