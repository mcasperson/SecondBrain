import argparse
import os
import re
from datetime import datetime, timedelta

import markdown2
from fpdf.enums import XPos, YPos
from fpdf.fpdf import FPDF


class PDF(FPDF):
    def header(self):
        self.set_font('DejaVu', '', 12)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, 'AI of Sauron', 0, align='C', new_x=XPos.LMARGIN, new_y=YPos.NEXT)

    def footer(self):
        self.set_y(-15)
        self.set_font('DejaVu', '', 8)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, f'Page {self.page_no()}', 0, align='C', new_x=XPos.RIGHT, new_y=YPos.TOP)

    def chapter_title(self, title):
        self.set_font('DejaVu', 'B', 24)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, title, 0, align='L', new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(10)

    def frontage_title(self, title):
        self.set_font('DejaVu', 'B', 48)
        self.set_text_color(255, 255, 0)
        self.cell(0, 10, title, 0, align='L', new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(10)

    def frontage_subtitle(self, title):
        self.set_font('DejaVu', 'B', 32)
        self.set_text_color(255, 255, 0)
        self.cell(0, 15, title, 0, align='L', new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(10)

    def frontage_dates(self, title):
        self.set_font('DejaVu', 'B', 32)
        self.set_text_color(255, 255, 0)
        self.cell(0, 0, title, 0, align='L', new_x=XPos.RIGHT, new_y=YPos.TOP)
        self.ln(10)

    def frontage_slack(self):
        self.set_font('DejaVu', 'B', 32)
        self.set_text_color(255, 255, 0)
        self.cell(0, 0, "#topic-solutions-engineeing", 0, align='L', new_x=XPos.RIGHT, new_y=YPos.TOP)
        self.ln(10)

    def chapter_body(self, body):
        self.set_font('DejaVu', '', 12)
        self.set_text_color(0, 0, 0)
        self.write_html(body, ul_bullet_char="•", li_prefix_color=(0, 0, 0))
        self.ln()


def convert_md_to_pdf(directory, output_pdf):
    # Get the directory of the current script
    script_dir = os.path.dirname(os.path.abspath(__file__))

    pdf = PDF()
    pdf.add_font('DejaVu', '', os.path.join(script_dir, 'fonts/roboto/Roboto-Regular.ttf'))
    pdf.add_font('DejaVu', 'B', os.path.join(script_dir, 'fonts/roboto/Roboto-Bold.ttf'))
    pdf.add_font('DejaVu', 'I', os.path.join(script_dir, 'fonts/roboto/Roboto-Italic.ttf'))
    pdf.add_font('DejaVu', 'BI', os.path.join(script_dir, 'fonts/roboto/Roboto-BoldItalic.ttf'))

    pdf.add_page()
    pdf.image(os.path.join(script_dir, 'logo.jpg'), x=0, y=0, w=pdf.w, h=pdf.h)
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
    pdf.frontage_slack()

    pdf.add_page()
    pdf.chapter_title("Introduction")
    pdf.chapter_body("""
    <p>Welcome to the AI of Sauron Monthly Customer Digest.</p>
    <p>This document is an AI generated summary the interactions between Octopus and our customers.</p>
    <p>Because it is AI generated, mistakes may occur. Please verify the information before taking any action.</p>
    """)

    contents = []
    for filename in sorted(os.listdir(directory)):
        if filename.endswith('.md'):
            title = os.path.splitext(filename)[0]  # Get file name without extension
            contents.append({'title': title, 'filename': filename, 'link': pdf.add_link()})

    # Move the Executive Summary to the top
    executive_summary = next((content for content in contents if content['title'] == 'Executive Summary'), None)
    if executive_summary:
        contents = [executive_summary] + list(filter(lambda content: content['title'] != 'Executive Summary', contents))

    # Add Table of Contents
    pdf.add_page()
    pdf.set_text_color(0, 0, 0)
    pdf.set_font('DejaVu', 'B', 16)
    pdf.cell(0, 10, 'Table of Contents', 0, 1, 'C')
    pdf.ln(10)
    pdf.set_font('DejaVu', '', 12)
    for content in contents:
        pdf.cell(0, 10, f'{content['title']}', 0, 1, 'L', link=content['link'])
    pdf.ln(10)

    for content in contents:
        filepath = os.path.join(directory, content['filename'])
        with open(filepath, 'r', encoding='utf-8') as file:
            pdf.add_page()
            pdf.set_link(content['link'])
            md_content = file.read()
            html_content = markdown2.markdown(md_content)

            # The generated HTML will often have nested lists with structures like this
            # <li><p><strong>License Management:</strong></p>
            # <ul>
            # <li>whatever</li>
            # </ul></li>
            # This doesn't render well, so we need to get rid of the paragraph tag

            html_content = re.sub(r'<li><p>(.*?)</p>', r'<li>\1', html_content)
            html_content = re.sub(r'<li><p>(.*?)</p></li>', r'<li>\1</li>', html_content, flags=re.DOTALL)

            pdf.chapter_title(content['title'])
            pdf.chapter_body(html_content)

    pdf.output(output_pdf)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Convert Markdown files to PDF.')
    parser.add_argument('directory', type=str, help='The directory containing Markdown files.')
    parser.add_argument('output_pdf', type=str, help='The output PDF file path.')

    args = parser.parse_args()
    convert_md_to_pdf(args.directory, args.output_pdf)
