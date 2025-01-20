import os
import markdown2
from fpdf import FPDF

class PDF(FPDF):
    def header(self):
        self.set_font('DejaVu', '', 12)
        self.cell(0, 10, 'Markdown to PDF', 0, 1, 'C')

    def footer(self):
        self.set_y(-15)
        self.set_font('DejaVu', '', 8)
        self.cell(0, 10, f'Page {self.page_no()}', 0, 0, 'C')

    def chapter_title(self, title):
        self.set_font('DejaVu', '', 12)
        self.cell(0, 10, title, 0, 1, 'L')
        self.ln(10)

    def chapter_body(self, body):
        self.set_font('DejaVu', '', 12)
        self.write_html(body)
        self.ln()

def convert_md_to_pdf(directory, output_pdf):
    pdf = PDF()
    pdf.add_font('DejaVu', '', '/home/matthew/Code/SecondBrain/scripts/publish/fonts/DejaVuSansCondensed.ttf')
    pdf.add_font('DejaVu', 'B', '/home/matthew/Code/SecondBrain/scripts/publish/fonts/DejaVuSansCondensed-Bold.ttf')
    pdf.add_font('DejaVu', 'I', '/home/matthew/Code/SecondBrain/scripts/publish/fonts/DejaVuSansCondensed-Oblique.ttf')
    pdf.add_page()

    for filename in os.listdir(directory):
        if filename.endswith(".md"):
            filepath = os.path.join(directory, filename)
            with open(filepath, 'r', encoding='utf-8') as file:
                md_content = file.read()
                html_content = markdown2.markdown(md_content)
                pdf.chapter_title(filename)
                pdf.chapter_body(html_content)

    pdf.output(output_pdf)

if __name__ == "__main__":
    directory = "/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches"
    output_pdf = "/home/matthew/Dropbox/output.pdf"
    convert_md_to_pdf(directory, output_pdf)