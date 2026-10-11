#!/usr/bin/env python3
"""Synthetic Docling-style archive for importer smoke-check, no external packages."""
import argparse, io, json, sqlite3, struct, tempfile, zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.parent.mkdir(parents=True, exist_ok=True)
book = "Example_Crane.pdf"
folder = "processed/Example_Crane__job42/"
rows = [
    {"schema": "docling-retrieval-index/v1", "postprocess_job_id": 42,
     "source_filename": book, "result_dir": "Example_Crane__job42", "chunk_id": f"example-{i}",
     "chunk_index": i, "page_numbers": [i+1], "headings": ["Example", "Electrical test"],
     "doc_items": [f"#/texts/{i}"], "content_type": "prose", "text": text}
    for i, text in enumerate([
        "Example-only: when fuse F1 trips, isolate power before inspection; check continuity per vessel safety procedures.",
        "Example-only: switch S2 indicates motor ready state. Voltage example is 24 V DC."
    ])
]
# Approved synthetic diagram matching Stage 2C visual_evidence_index.jsonl.
visual_row = {
    "schema": "docling-rag-visual-index/v1", "evidence_kind": "visual",
    "postprocess_job_id": 42, "source_filename": book,
    "chunk_id": "V-42-000000", "picture_index": 0,
    "page_numbers": [1], "doc_items": ["#/pictures/0"],
    "rag_eligible": True, "category": "wiring_diagram",
    "artifact": "artifacts/image_000000.png",
    "text": "wiring diagram: Fuse F1 protects the contactor supply. Arrow indicates DC polarity."
}
# Minimal valid two-page PDF generated without third-party libs.
def make_pdf():
    objects = []
    def obj(body): objects.append(body); return len(objects)
    root = obj("<< /Type /Catalog /Pages 2 0 R >>")
    pages = obj("<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
    for pageid, contentid, words in [(3,4,"Example Source PDF - page 1"),(5,6,"Example Source PDF - page 2")]:
        obj(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 7 0 R >> >> /Contents {contentid} 0 R >>")
        stream = f"BT /F1 16 Tf 40 760 Td ({words}) Tj ET".encode()
        obj(f"<< /Length {len(stream)} >>\nstream\n"+stream.decode()+"\nendstream")
    obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    pdf=bytearray(b"%PDF-1.4\n"); offsets=[0]
    for i, body in enumerate(objects,1):
        offsets.append(len(pdf)); pdf+=f"{i} 0 obj\n{body}\nendobj\n".encode()
    start=len(pdf); pdf+=f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode()
    for pos in offsets[1:]: pdf+=f"{pos:010d} 00000 n \n".encode()
    pdf+=f"trailer << /Root 1 0 R /Size {len(objects)+1} >>\nstartxref\n{start}\n%%EOF\n".encode()
    return bytes(pdf)

vectors = [1.0,0.0,0.0,1.0]
meta = {"schema":"docling-equipment-embedding-index/v2","equipment_id":"example_crane",
        "model":"demo-test-embeddings","dim":2,"rows":2,"document_prefix":""}
with tempfile.TemporaryDirectory() as td:
    dbpath=Path(td)/"jobs.db"; connection=sqlite3.connect(dbpath);connection.execute("CREATE TABLE placeholder(id INTEGER)");connection.close()
    converted=io.BytesIO()
    with zipfile.ZipFile(converted,"w",zipfile.ZIP_DEFLATED) as z:
        z.writestr("example_docling.json",json.dumps({"texts":[{"text":"Example"}],
            "pictures":[{"image":{"uri":"artifacts/image_000000.png","mimetype":"image/png"},
                         "prov":[{"page_no":1}]}]}))
        import base64
        z.writestr("artifacts/image_000000.png",base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/0aIAAAAASUVORK5CYII="))
    with zipfile.ZipFile(args.output,"w",zipfile.ZIP_DEFLATED) as z:
        z.writestr(folder+"retrieval_index.jsonl","\n".join(json.dumps(x) for x in rows)+"\n")
        z.writestr(folder+"visual_evidence_index.jsonl",json.dumps(visual_row)+"\n")
        z.writestr(folder+"source_manifest.json",json.dumps({"source_filename":book,"converted_zip":"Example_Crane_converted.zip"}))
        z.writestr("input/"+book, make_pdf())
        z.writestr("output/Example_Crane_converted.zip",converted.getvalue())
        z.write(dbpath,"db/jobs.db")
        base="processed/equipment_embedding_index/example_crane/demo-test-embeddings/"
        z.writestr(base+"metadata.json",json.dumps(meta))
        z.writestr(base+"rows.jsonl","\n".join(json.dumps(x) for x in rows)+"\n")
        z.writestr(base+"vectors.f32",struct.pack("<4f",*vectors))
print(f"Created {args.output} ({args.output.stat().st_size} bytes)")