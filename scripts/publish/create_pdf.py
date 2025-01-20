import markdown2
import os
from datetime import datetime, timedelta
from fpdf import FPDF


class PDF(FPDF):
    def header(self):
        self.set_font('DejaVu', '', 12)
        self.cell(0, 10, 'AI of Sauron', 0, 1, 'C')

    def footer(self):
        self.set_y(-15)
        self.set_font('DejaVu', '', 8)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, f'Page {self.page_no()}', 0, 0, 'C')

    def chapter_title(self, title):
        self.set_font('DejaVu', '', 12)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, title, 0, 1, 'L')
        self.ln(10)

    def frontage_title(self, title):
        self.set_font('DejaVu', '', 48)
        self.set_text_color(255, 255, 255)
        self.cell(0, 10, title, 0, 1, 'L')
        self.ln(10)

    def frontage_subtitle(self, title):
        self.set_font('DejaVu', '', 32)
        self.set_text_color(255, 255, 255)
        self.cell(0, 15, title, 0, 1, 'L')
        self.ln(10)

    def frontage_dates(self, title):
        self.set_font('DejaVu', '', 32)
        self.set_text_color(255, 255, 255)
        self.cell(0, 0, title, 0, 1, 'L')
        self.ln(10)

    def chapter_body(self, body):
        self.set_font('DejaVu', '', 12)
        self.set_text_color(0, 0, 0)
        self.write_html(body)
        self.ln()


def convert_md_to_pdf(directory, output_pdf):
    pdf = PDF()
    pdf.add_font('DejaVu', '', '/home/matthew/Code/SecondBrain/scripts/publish/fonts/DejaVuSansCondensed.ttf')
    pdf.add_font('DejaVu', 'B', '/home/matthew/Code/SecondBrain/scripts/publish/fonts/DejaVuSansCondensed-Bold.ttf')
    pdf.add_font('DejaVu', 'I', '/home/matthew/Code/SecondBrain/scripts/publish/fonts/DejaVuSansCondensed-Oblique.ttf')

    pdf.add_page()
    pdf.image('/home/matthew/Code/SecondBrain/scripts/publish/logo.jpg', x=0, y=0, w=pdf.w, h=pdf.h)
    pdf.frontage_title('AI of Sauron')
    pdf.frontage_subtitle('Monthly Customer Digest')

    # Get the current date and time
    now = datetime.now()
    start_of_last_month = (now - timedelta(days=1)).replace(day=1)
    formatted_date = start_of_last_month.strftime('%Y-%m-%d')

    first_day_next_month = (now.replace(day=28) + timedelta(days=4)).replace(day=1)
    last_day_of_month = first_day_next_month - timedelta(days=1)
    formatted_end_date = last_day_of_month.strftime('%Y-%m-%d')

    pdf.frontage_dates(f'{formatted_date} to {formatted_end_date}')

    for filename in os.listdir(directory):
        if filename.endswith(".md"):
            filepath = os.path.join(directory, filename)
            with open(filepath, 'r', encoding='utf-8') as file:
                pdf.add_page()
                md_content = file.read()
                html_content = markdown2.markdown(md_content)
                pdf.chapter_title(os.path.splitext(filename)[0])
                pdf.chapter_body(html_content)

    pdf.output(output_pdf)


if __name__ == "__main__":
    directory = "/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches"
    output_pdf = "/home/matthew/Dropbox/output.pdf"
    convert_md_to_pdf(directory, output_pdf)
