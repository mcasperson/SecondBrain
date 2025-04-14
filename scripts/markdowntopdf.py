import os
import glob
import tempfile
import subprocess
from pathlib import Path
import argparse
from fpdf import FPDF
import markdown
from bs4 import BeautifulSoup
import re


def convert_md_to_pdf(input_dir, output_pdf, cover_image):
    """
    Converts markdown files from a directory into a single PDF with cover page and table of contents.

    Args:
        input_dir (str): Directory containing markdown files
        output_pdf (str): Path to save the output PDF
        cover_image (str): Path to cover image (PNG)
    """
    # Find all markdown files
    md_files = sorted(glob.glob(os.path.join(input_dir, "*.md")))

    if not md_files:
        print(f"No markdown files found in {input_dir}")
        return

    # Create PDF object
    pdf = FPDF()

    font_dir = "publish/fonts/roboto"
    pdf.add_font('Roboto', '', os.path.join(font_dir, 'Roboto-Regular.ttf'))
    pdf.add_font('Roboto', 'B', os.path.join(font_dir, 'Roboto-Bold.ttf'))
    pdf.add_font('Roboto', 'I', os.path.join(font_dir, 'Roboto-Italic.ttf'))
    pdf.add_font('Roboto', 'BI', os.path.join(font_dir, 'Roboto-BoldItalic.ttf'))
    
    pdf.set_auto_page_break(True, margin=15)
    pdf.add_page()

    # Add cover page with background image
    pdf.image(cover_image, x=0, y=0, w=210, h=297)  # A4 size
    pdf.set_font("Roboto", "B", 24)
    pdf.set_text_color(255, 255, 255)  # White text
    pdf.cell(0, 40, "AI of Sauron", 0, 1, "C")
    pdf.set_font("Roboto", "", 14)
    pdf.cell(0, 10, f"AutoCDJ", 0, 1, "C")

    # Add TOC page
    pdf.add_page()
    pdf.set_font("Roboto", "B", 16)
    pdf.set_text_color(0, 0, 0)  # Black text
    pdf.cell(0, 20, "Table of Contents", 0, 1, "C")
    pdf.set_font("Roboto", "", 12)

    # Create links dictionary to store page numbers and link objects
    links = {}
    current_page = 3  # Start after cover and TOC pages

    # First pass: determine page numbers for TOC
    for md_file in md_files:
        file_name = os.path.basename(md_file)
        title = Path(md_file).stem

        # Parse markdown to estimate page count
        with open(md_file, 'r', encoding='utf-8') as f:
            md_content = f.read()

        html = markdown.markdown(md_content)
        soup = BeautifulSoup(html, 'html.parser')
        text_content = soup.get_text()

        # Rough estimate: 2000 characters per page
        pages_estimate = max(1, len(text_content) // 2000)

        links[title] = {'page': current_page, 'link': None}
        current_page += pages_estimate

    # Second pass: add content and create link destinations
    for md_file in md_files:
        with open(md_file, 'r', encoding='utf-8') as f:
            md_content = f.read()

        # Convert markdown to HTML
        html = markdown.markdown(md_content)
        soup = BeautifulSoup(html, 'html.parser')

        # Extract text content
        title = Path(md_file).stem

        # Add a new page for each document
        pdf.add_page()
        
        # Create a link anchor at the start of each chapter
        links[title]['link'] = pdf.add_link()
        pdf.set_link(links[title]['link'])

        # Add title
        pdf.set_font("Roboto", "B", 16)
        pdf.cell(0, 15, title, 0, 1, "L")
        pdf.line(10, pdf.get_y(), 200, pdf.get_y())
        pdf.ln(5)

        # Process and add content
        pdf.set_font("Roboto", "", 12)

        # Process headings and paragraphs
        for element in soup.find_all(['h1', 'h2', 'h3', 'h4', 'p', 'ul', 'ol']):
            if element.name.startswith('h'):
                level = int(element.name[1])
                pdf.set_font("Roboto", "B", 16 - level)
                pdf.cell(0, 10, element.get_text(), 0, 1)
                pdf.ln(2)
            elif element.name == 'p':
                pdf.set_font("Roboto", "", 12)
                pdf.multi_cell(pdf.w - 20, 6, element.get_text())
                pdf.ln(3)
            elif element.name in ['ul', 'ol']:
                pdf.set_font("Roboto", "", 12)
                for li in element.find_all('li'):
                    pdf.cell(10, 6, "•", 0, 0)
                    pdf.multi_cell(pdf.w - 30, 6, li.get_text().strip())
                    pdf.ln(1)

        pdf.ln(5)

    # Go back to TOC page and add clickable entries
    pdf.page = 2  # TOC page
    pdf.set_font("Roboto", "", 12)
    pdf.set_y(40)  # Position after the TOC title
    
    for md_file in md_files:
        title = Path(md_file).stem
        pdf.set_text_color(0, 0, 255)  # Blue text for links
        pdf.cell(0, 10, f"{title}", 0, 1, link=links[title]['link'])
        pdf.set_text_color(0, 0, 0)  # Black text
        pdf.cell(0, 2, f"Page {links[title]['page']}", 0, 1, "R")

    # Save PDF
    pdf.output(output_pdf)
    print(f"PDF created: {output_pdf}")


def main():
    parser = argparse.ArgumentParser(description='Convert markdown files to PDF with cover page and TOC')
    parser.add_argument('--input_dir', required=True, help='Directory containing markdown files')
    parser.add_argument('--output_pdf', required=True, help='Output PDF file path')
    parser.add_argument('--cover_image', required=True, help='Cover page background image (PNG)')

    args = parser.parse_args()

    # Validate input directory
    if not os.path.isdir(args.input_dir):
        print(f"Error: {args.input_dir} is not a valid directory")
        return

    # Validate cover image
    if not os.path.isfile(args.cover_image) or not args.cover_image.lower().endswith('.png'):
        print(f"Error: Cover image must be a PNG file")
        return

    convert_md_to_pdf(args.input_dir, args.output_pdf, args.cover_image)


if __name__ == "__main__":
    main()
